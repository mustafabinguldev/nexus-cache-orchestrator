package network.darkland.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import network.darkland.NexusApplication;
import network.darkland.cache.L1InvalidationBus;
import network.darkland.model.DataModel;
import network.darkland.protocol.NexusJsonDataContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RedisDataContainer {

    private static final Logger LOGGER = Logger.getLogger(RedisDataContainer.class.getName());

    private static final int RECONCILE_BATCH_SIZE = 50;

    private static final long L1_MAX_ENTRIES = 100_000;

    private final Cache<String, DataModel> keyToModel;

    private final ConcurrentHashMap<String, String> idToKey;

    private final Set<String> dirtyKeys;
    private record PendingWrite(DataModel model, String json) {}
    private final ConcurrentHashMap<String, PendingWrite> pendingWrites = new ConcurrentHashMap<>();
    private final Object[] flushLocks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new Object()).toArray();

    private Object flushLock(String key) { return flushLocks[(key.hashCode() & 0x7fffffff) % flushLocks.length]; }

    public void markDirty(DataModel model) {
        String key = model.getKey();
        pendingWrites.compute(key, (k, old) -> {
            dirtyKeys.add(k);
            return new PendingWrite(model, model.getValueJson());
        });
        NexusApplication.getApplication().getRedisManager().trackDirty(key);
    }

    public Optional<DataModel> getPendingModel(String key) {
        PendingWrite pending = pendingWrites.get(key);
        return pending == null ? Optional.empty() : Optional.of(pending.model());
    }

    public void flushKey(String key) {
        synchronized (flushLock(key)) {
            PendingWrite pending = pendingWrites.get(key);
            if (pending == null) return;
            NexusApplication.getApplication().getMongoManager()
                    .setValue(pending.model().getAddon(), pending.model().getSpecificDbKey(), pending.json()).join();
            pendingWrites.compute(key, (k, current) -> {
                if (current != pending) return current;
                dirtyKeys.remove(k);
                return null;
            });
        }
    }

    private final L1InvalidationBus invalidationBus;

    public RedisDataContainer() {
        this.idToKey    = new ConcurrentHashMap<>();
        this.dirtyKeys  = ConcurrentHashMap.newKeySet();
        Expiry<String, DataModel> perAddonExpiry = new Expiry<>() {
            @Override
            public long expireAfterCreate(String key, DataModel model, long currentTime) {
                int ttlSeconds = model.getAddon().getCacheTTL();
                return TimeUnit.SECONDS.toNanos(Math.max(ttlSeconds, 1));
            }

            @Override
            public long expireAfterUpdate(String key, DataModel model, long currentTime, long currentDuration) {
                int ttlSeconds = model.getAddon().getCacheTTL();
                return TimeUnit.SECONDS.toNanos(Math.max(ttlSeconds, 1));
            }

            @Override
            public long expireAfterRead(String key, DataModel model, long currentTime, long currentDuration) {
                return currentDuration;
            }
        };

        this.keyToModel = Caffeine.newBuilder()
                .maximumSize(L1_MAX_ENTRIES)
                .expireAfter(perAddonExpiry)
                .removalListener((String key, DataModel model, RemovalCause cause) -> {
                    if (model != null) {
                        idToKey.remove(model.getId(), key);
                    }
                })
                .recordStats()
                .build();

        this.invalidationBus = new L1InvalidationBus(this::removeModelLocal);
        this.invalidationBus.start();

        RedisManager rm = NexusApplication.getApplication().getRedisManager();
        rm.scheduleTask(this::startL1SyncTask,         10, 10, TimeUnit.SECONDS);
        rm.scheduleTask(this::startAutoFlushTask,      15, 15, TimeUnit.SECONDS);
        rm.scheduleTask(this::startReconciliationTask,  3,  3, TimeUnit.MINUTES);

        rm.scheduleTask(this::sendNetworkLiveBroadcast, 1, 1, TimeUnit.SECONDS);
        startL1SyncListener();
    }

    private void sendNetworkLiveBroadcast() {
        NexusApplication.getApplication().getRedisManager().processTask(() -> {
            NexusJsonDataContainer jsonDataContainer = new NexusJsonDataContainer();
            jsonDataContainer.set("type", "LIVE");
            jsonDataContainer.set("source", "nexus");
            jsonDataContainer.set("time", System.currentTimeMillis() / 1000L);

            NexusApplication.getApplication().getRedisManager()
                    .publish("darkland_nexus_live", MessageAuth.stamp(jsonDataContainer.toFullJson()));
        });
    }

    public void startL1SyncListener() {
        new Thread(() -> {
            String expiredChannel = "__keyevent@0__:expired";

            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = NexusApplication.getApplication().getRedisManager().getPool().getResource()) {
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            removeModelLocal(message);
                            LOGGER.warning("[Nexus] Key expired: " + message + ", Removing from L1 cache to maintain data integrity.");
                        }
                    }, expiredChannel);
                } catch (Exception e) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "Nexus-L1-Sync-Thread").start();
    }

    private void startL1SyncTask() {
        if (keyToModel.asMap().isEmpty()) return;

        RedisManager rm = NexusApplication.getApplication().getRedisManager();

        rm.processTask(() -> keyToModel.asMap().forEach((key, model) -> {
            if (dirtyKeys.contains(key)) return;

            Optional<String> redisOpt = rm.getData(key);

            if (redisOpt.isPresent()) {
                String redisJson = redisOpt.get();
                if (!redisJson.equals(model.getValueJson())) {
                    model.setValueJson(redisJson);
                }
            } else {
                LOGGER.warning("[L1Sync] Redis key kayıp, restore ediliyor: " + key);
                rm.setData(key, model.getValueJson(), model.getAddon());
                markDirty(model);
            }
        }));
    }

    private void startAutoFlushTask() {
        if (dirtyKeys.isEmpty()) return;

        List<String> keysToFlush = new ArrayList<>(dirtyKeys);

        RedisManager rm = NexusApplication.getApplication().getRedisManager();

        rm.processMongoTask(() -> {
            for (String key : keysToFlush) {
                try {
                    flushKey(key);

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE,
                            "[AutoFlush] Mongo write failed, will retry.: " + key, e);
                }
            }
        });
    }

    private void startReconciliationTask() {
        if (keyToModel.asMap().isEmpty()) return;

        RedisManager rm = NexusApplication.getApplication().getRedisManager();
        rm.processMongoTask(() -> {
            List<String> keys  = new ArrayList<>(keyToModel.asMap().keySet());
            List<CompletableFuture<?>> batch = new ArrayList<>(RECONCILE_BATCH_SIZE);

            for (String key : keys) {
                if (dirtyKeys.contains(key)) continue;

                DataModel model = keyToModel.getIfPresent(key);
                if (model == null) continue;

                String redisJson = rm.getData(key).orElseGet(model::getValueJson);

                CompletableFuture<?> future = NexusApplication.getApplication()
                        .getMongoManager()
                        .getValue(model.getAddon(), model.getSpecificDbKey())
                        .thenAccept(dbJson -> {
                            synchronized (flushLock(key)) {
                                // Do not let a reconciliation snapshot overwrite a newer mutation or deletion.
                                if (dirtyKeys.contains(key) || keyToModel.getIfPresent(key) != model
                                        || !redisJson.equals(model.getValueJson())) return;
                                String cleanDbJson = dbJson == null ? null : model.getAddon().modelInitComp(dbJson);
                                if (!redisJson.equals(cleanDbJson)) {
                                    NexusApplication.getApplication().getMongoManager()
                                            .setValue(model.getAddon(), model.getSpecificDbKey(), redisJson).join();
                                }
                            }
                        })
                        .exceptionally(ex -> {
                            LOGGER.log(Level.WARNING,
                                    "[Reconciliation] Mongo read error: " + key, ex);
                            return null;
                        });

                batch.add(future);

                if (batch.size() >= RECONCILE_BATCH_SIZE) {
                    waitForBatch(batch);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) waitForBatch(batch);
        });
    }

    private void waitForBatch(List<CompletableFuture<?>> batch) {
        try {
            CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Reconciliation] Batch waiting error", e);
        }
    }

    public void addModel(String key, DataModel model) {
        markDirty(model);
        writeToL1AndRedis(key, model);
    }

    public void addModelFix(String key, DataModel model) {
        addModel(key, model);
    }

    public void cacheModel(String key, DataModel model) {
        writeToL1AndRedis(key, model);
    }

    public void addModelDirect(String key, DataModel model) {
        markDirty(model);
        writeToL1AndRedis(key, model);
        NexusApplication.getApplication().getRedisManager().processMongoTask(() -> {
            try {
                flushKey(key);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "[addModelDirect] Mongo write failed: " + key, e);
                throw new IllegalStateException("Initial model write failed", e);
            }
        });
    }

    public void broadcastRemoteInvalidation(String key) {
        invalidationBus.publishInvalidation(key);
    }

    public void removeModel(String key) {
        flushKey(key);
        removeModelLocal(key);
        invalidationBus.publishInvalidation(key);
        NexusApplication.getApplication().getRedisManager().deleteData(key);
    }

    private void removeModelLocal(String key) {
        DataModel removed = keyToModel.asMap().remove(key);
        if (removed == null) return;

        idToKey.remove(removed.getId(), key);
    }

    public Optional<DataModel> getDataModelFromId(String id) {
        String key = idToKey.get(id);
        if (key == null) return Optional.empty();
        return Optional.ofNullable(keyToModel.getIfPresent(key));
    }

    public Optional<DataModel> getDataModelFromKey(String key) {
        return Optional.ofNullable(keyToModel.getIfPresent(key));
    }

    public Set<String> getDirtyKeys() { return dirtyKeys; }

    public int getDataSize() { return (int) keyToModel.estimatedSize(); }

    public com.github.benmanes.caffeine.cache.stats.CacheStats getL1Stats() {
        return keyToModel.stats();
    }

    private void writeToL1AndRedis(String key, DataModel model) {
        if (model.getAddon().l1CacheEnabled()) {
            keyToModel.put(key, model);
            idToKey.put(model.getId(), key);
        }
        NexusApplication.getApplication().getRedisManager().setData(key, model.getValueJson(), model.getAddon());
    }
}
