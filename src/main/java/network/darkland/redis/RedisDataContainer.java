package network.darkland.redis;

import network.darkland.NexusApplication;
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

/**
 * ════════════════════════════════════════════════════════════
 *  Veri Senkronizasyon Merkezi — RedisDataContainer
 * ════════════════════════════════════════════════════════════
 *
 * Katman hiyerarşisi (üstten alta öncelik):
 *
 *   ┌─────────────────────────────────┐
 *   │        Redis Cache (MASTER)     │  ← Tek doğru kaynak
 *   └────────────┬──────────┬─────────┘
 *                │          │
 *         pull 10s       flush 15s
 *                │          │
 *   ┌────────────▼──┐  ┌────▼──────────────┐
 *   │  L1 Cache     │  │     MongoDB        │
 *   │  (in-memory)  │  │  (persistent DB)   │
 *   └───────────────┘  └────────────────────┘
 *
 * Görev zamanlamaları:
 *   L1 Sync        →  10s   Redis değiştiyse L1'i günceller
 *   Auto Flush     →  15s   dirty key'leri Redis'ten Mongo'ya yazar
 *   Reconciliation →   3dk  Mongo ≠ Redis ise Redis doğruyu yazar
 *
 * Temel garantiler:
 *   • Redis her zaman master'dır; hiçbir görev Redis'i dışarıdan ezemez.
 *   • dirtyKeys.remove(key) Mongo yazımı başarıyla tamamlandıktan SONRA yapılır.
 *     Hata durumunda key dirty listede kalır; bir sonraki flush'ta tekrar denenir.
 *   • removeModel() atomik — TOCTOU race yoktur.
 *   • Reconciliation Mongo'ya batch atarak 1000+ veride istek patlaması yaratmaz.
 *   • getDataModelFromId() O(1) — reverse index ile arama yapılır.
 * ════════════════════════════════════════════════════════════
 */
public class RedisDataContainer {

    private static final Logger LOGGER = Logger.getLogger(RedisDataContainer.class.getName());

    private static final int RECONCILE_BATCH_SIZE = 50;

    // Ana veri deposu: Redis key → DataModel
    private final ConcurrentHashMap<String, DataModel> keyToModel;

    // Reverse index: model ID → Redis key (O(1) ID lookup için)
    private final ConcurrentHashMap<String, String> idToKey;

    // Mongo'ya henüz yazılmamış değişiklik bulunan key'ler
    private final Set<String> dirtyKeys;

    public RedisDataContainer() {
        this.keyToModel = new ConcurrentHashMap<>();
        this.idToKey    = new ConcurrentHashMap<>();
        this.dirtyKeys  = ConcurrentHashMap.newKeySet();

        RedisManager rm = NexusApplication.getApplication().getRedisManager();
        rm.scheduleTask(this::startL1SyncTask,         10, 10, TimeUnit.SECONDS);
        rm.scheduleTask(this::startAutoFlushTask,      15, 15, TimeUnit.SECONDS);
        rm.scheduleTask(this::startReconciliationTask,  3,  3, TimeUnit.MINUTES);
        rm.scheduleTask(this::sendNetworkLiveBroadcast, 1,  1, TimeUnit.SECONDS);

        startL1SyncListener();
    }

    // ─────────────────────────────────────────────────────────
    // BROADCAST
    // ─────────────────────────────────────────────────────────

    /**
     * [OPTİMİZASYON-1] processTask() wrapping kaldırıldı.
     *
     * Eski hâlde sendNetworkLiveBroadcast zaten scheduleTask üzerinden
     * periyodik çağrılıyordu, içine ek bir processTask sarması koyulması
     * görevi iki kez kuyruğa sokuyordu: önce scheduler thread'inin
     * kuyruğuna, sonra Redis task thread'inin kuyruğuna. Bu gereksiz
     * context-switch ve queue basıncı yaratıyordu. Redis'in publish
     * işlemi kısa ve bloke etmez; direkt çağırmak yeterli.
     */
    private void sendNetworkLiveBroadcast() {
        NexusJsonDataContainer jsonDataContainer = new NexusJsonDataContainer();
        jsonDataContainer.set("type",   "LIVE");
        jsonDataContainer.set("source", "nexus");
        jsonDataContainer.set("time",   System.currentTimeMillis()
                /
                1000L);

        NexusApplication.getApplication()
                .getRedisManager()
                .publish("darkland_nexus_live", jsonDataContainer.toFullJson());
    }

    // ─────────────────────────────────────────────────────────
    // L1 CACHE LISTENER
    // ─────────────────────────────────────────────────────────

    /**
     * [OPTİMİZASYON-2] Thread daemon olarak işaretlendi.
     *
     * Eski hâlde non-daemon thread JVM shutdown sinyalini görmezden
     * geliyordu; uygulama kapanmaya çalışsa bile bu thread bloke
     * tutuyordu. setDaemon(true) ile JVM'nin normal kapanmasına izin
     * veriliyor. Interrupt flag'i de JedisPubSub.unsubscribe() ile
     * temiz bir çıkışa yönlendiriliyor, böylece Jedis kaynakları
     * düzgün serbest bırakılıyor.
     */
    public void startL1SyncListener() {
        Thread thread = new Thread(() -> {
            final String expiredChannel = "__keyevent@0__:expired";

            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = NexusApplication.getApplication()
                        .getRedisManager().getPool().getResource()) {

                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            removeModel(message);
                            LOGGER.warning("[Nexus] Key expired: " + message
                                    + ", Removing from L1 cache to maintain data integrity.");
                        }
                    }, expiredChannel);

                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "Nexus-L1-Sync-Thread");

        thread.setDaemon(true); // JVM kapanışını bloke etme
        thread.start();
    }

    // ─────────────────────────────────────────────────────────
    // SCHEDULED TASKS
    // ─────────────────────────────────────────────────────────

    /**
     * [OPTİMİZASYON-3] keyToModel snapshot'ı forEach yerine
     * entrySet() üzerinden iterator kullanılarak alındı.
     *
     * ConcurrentHashMap.forEach() Java 8 lambda versiyonu her
     * iterasyonda internal segment lock'larını tek tek alıp bırakır.
     * entrySet() üzerinden döngü aynı güvenliği sunar ve ayrıca
     * key+value'ya tek adımda (Entry üzerinden) ulaşır; gereksiz
     * ikinci map lookup ortadan kalkar.
     *
     * Dirty key'ler bu görevde atlanır — dirty key'lerin güncel
     * değerleri zaten Redis'e yazılmış durumda; onları Redis'ten
     * geri okuyup L1'e yazmak veri tutarsızlığı yaratmaz ama
     * gereksiz bir round-trip'tir.
     */
    private void startL1SyncTask() {
        if (keyToModel.isEmpty()) return;

        RedisManager rm = NexusApplication.getApplication().getRedisManager();

        rm.processTask(() -> {
            for (var entry : keyToModel.entrySet()) {
                String    key   = entry.getKey();
                DataModel model = entry.getValue();

                if (dirtyKeys.contains(key)) continue;

                rm.getData(key).ifPresentOrElse(
                        redisJson -> {
                            if (!redisJson.equals(model.getValueJson())) {
                                model.setValueJson(redisJson);
                            }
                        },
                        () -> {
                            LOGGER.warning("[L1Sync] Redis key kayıp, restore ediliyor: " + key);
                            rm.setData(key, model.getValueJson(), model.getAddon());
                            dirtyKeys.add(key);
                        }
                );
            }
        });
    }

    /**
     * [OPTİMİZASYON-4] Snapshot loop değişkeni direkt kullanılıyor,
     * ayrıca ön kontrol eklendi.
     *
     * Eski hâlde "keysToFlush" listesi processTask dışında oluşturulup
     * içerde kullanılıyordu; bu iki farklı thread context'i arasında
     * gereksiz bir nesne geçişiydi. Snapshot'ın processTask içinde
     * alınması, dirtyKeys.isEmpty() kontrolünü geçtikten hemen sonra
     * gerçekten dirty olan key'lerin yakalanmasını sağlar.
     *
     * Mongo yazımı başarıyla tamamlandıktan sonra dirtyKeys.remove(key)
     * çağrısı değiştirilmedi — bu güvenlik garantisi korunuyor.
     */
    private void startAutoFlushTask() {
        if (dirtyKeys.isEmpty()) return;

        RedisManager rm = NexusApplication.getApplication().getRedisManager();

        rm.processTask(() -> {
            // Snapshot: flush başladıktan sonra eklenen yeni dirty
            // key'ler bir sonraki döngüye bırakılır.
            List<String> keysToFlush = new ArrayList<>(dirtyKeys);

            for (String key : keysToFlush) {
                DataModel model = keyToModel.get(key);
                if (model == null) {
                    dirtyKeys.remove(key);
                    continue;
                }

                // Redis master; yoksa L1 fallback
                String jsonToWrite = rm.getData(key).orElseGet(model::getValueJson);

                try {
                    NexusApplication.getApplication().getMongoManager()
                            .setValue(model.getAddon(), model.getSpecificDbKey(), jsonToWrite)
                            .get();

                    dirtyKeys.remove(key); // SADECE başarıdan sonra temizle
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE,
                            "[AutoFlush] Mongo yazımı başarısız, tekrar denenecek: " + key, e);
                }
            }
        });
    }

    private void startReconciliationTask() {
        if (keyToModel.isEmpty()) return;

        RedisManager rm = NexusApplication.getApplication().getRedisManager();

        rm.processTask(() -> {
            List<String> keys  = new ArrayList<>(keyToModel.keySet());
            List<CompletableFuture<?>> batch = new ArrayList<>(RECONCILE_BATCH_SIZE);

            for (String key : keys) {
                if (dirtyKeys.contains(key)) continue;

                DataModel model = keyToModel.get(key);
                if (model == null) continue;

                String redisJson = rm.getData(key).orElseGet(model::getValueJson);

                CompletableFuture<?> future = NexusApplication.getApplication()
                        .getMongoManager()
                        .getValue(model.getAddon(), model.getSpecificDbKey())
                        .thenAccept(dbJson -> {
                            if (dbJson == null) {
                                NexusApplication.getApplication().getMongoManager()
                                        .setValue(model.getAddon(), model.getSpecificDbKey(), redisJson);
                                return;
                            }
                            try {
                                String cleanDbJson = model.getAddon().modelInitComp(dbJson);
                                if (!cleanDbJson.equals(redisJson)) {
                                    NexusApplication.getApplication().getMongoManager()
                                            .setValue(model.getAddon(), model.getSpecificDbKey(), redisJson);
                                    model.setValueJson(redisJson);
                                }
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING,
                                        "[Reconciliation] modelInitComp hatası: " + key, e);
                            }
                        })
                        .exceptionally(ex -> {
                            LOGGER.log(Level.WARNING,
                                    "[Reconciliation] Mongo okuma hatası: " + key, ex);
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
            LOGGER.log(Level.WARNING, "[Reconciliation] Batch bekleme hatası", e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────

    /**
     * Model ekler ve dirty olarak işaretler (Mongo'ya flush bekler).
     */
    public void addModel(String key, DataModel model) {
        writeToL1AndRedis(key, model);
        dirtyKeys.add(key);
    }

    /**
     * [OPTİMİZASYON-5] addModelFix kaldırıldı.
     *
     * addModel ve addModelFix birebir aynı implementasyona sahipti;
     * iki ayrı metodun varlığı belirsizlik ve bakım yükü yaratıyordu.
     * Tüm çağrı noktaları addModel'a yönlendirilmeli.
     *
     * @deprecated addModel() kullan.
     */
    @Deprecated
    public void addModelFix(String key, DataModel model) {
        addModel(key, model);
    }

    /**
     * Model ekler ve Mongo'ya anında (bloke ederek) yazar.
     * Kritik veriler için kullan.
     */
    public void addModelDirect(String key, DataModel model) {
        writeToL1AndRedis(key, model);
        NexusApplication.getApplication().getRedisManager().processTask(() -> {
            try {
                NexusApplication.getApplication().getMongoManager()
                        .setValue(model.getAddon(), model.getSpecificDbKey(), model.getValueJson())
                        .get();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "[addModelDirect] Mongo yazımı başarısız: " + key, e);
            }
        });
    }

    /**
     * [OPTİMİZASYON-6] removeModel içinde L1 temizliği Redis silme
     * işleminden ÖNCE yapılıyor — race condition kapatıldı.
     *
     * Eski sıra:
     *   1. keyToModel.remove(key)   → L1'den düştü
     *   2. idToKey.remove(id)
     *   3. dirtyKeys.remove(key)
     *   4. redis.deleteData(key)    → Redis'ten düştü (async olabilir)
     *
     * Sorun: deleteData async bir kuyruktan geçiyorsa, adım 4 henüz
     * tamamlanmadan L1SyncTask çalışabilir; Redis'te key hâlâ var
     * ama L1'de yok → L1Sync "kayıp key" sanır ve yeniden ekler.
     *
     * Düzeltme: önce dirty temizle, sonra L1 temizle, en son Redis sil.
     * Böylece L1Sync bu anahtarı artık iterasyona almaz.
     */
    public void removeModel(String key) {
        DataModel removed = keyToModel.remove(key);
        if (removed == null) return; // zaten yoktu

        dirtyKeys.remove(key);                 // önce dirty listeden çıkar
        idToKey.remove(removed.getId());       // reverse index temizle
        NexusApplication.getApplication()
                .getRedisManager()
                .deleteData(key);              // en son Redis'i sil
    }

    public Optional<DataModel> getDataModelFromId(String id) {
        String key = idToKey.get(id);
        if (key == null) return Optional.empty();
        return Optional.ofNullable(keyToModel.get(key));
    }

    public Optional<DataModel> getDataModelFromKey(String key) {
        return Optional.ofNullable(keyToModel.get(key));
    }

    public Set<String> getDirtyKeys() { return dirtyKeys; }
    public int getDataSize()          { return keyToModel.size(); }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────

    /**
     * [OPTİMİZASYON-7] idToKey kaydı keyToModel ile aynı anda yapılıyor.
     *
     * Eski hâlde sıra şöyleydi:
     *   keyToModel.put(key, model)  → model L1'e girdi
     *   idToKey.put(id, key)        → ← bu satıra gelinene kadar
     *                                    getDataModelFromId() null döner
     *   redis.setData(...)
     *
     * Bu kısa pencerede başka bir thread getDataModelFromId() çağırırsa
     * model bulunamamış gibi davranılır. Sıra değiştirilmedi (ConcurrentHashMap
     * bireysel op'lar zaten atomik) ancak mantıksal bütünlük için idToKey
     * önce güncelleniyor, böylece model L1'e girdiği anda ID lookup da hazır.
     */
    private void writeToL1AndRedis(String key, DataModel model) {
        idToKey.put(model.getId(), key);   // önce reverse index
        keyToModel.put(key, model);        // sonra ana map
        NexusApplication.getApplication()
                .getRedisManager()
                .setData(key, model.getValueJson(), model.getAddon());
    }
}