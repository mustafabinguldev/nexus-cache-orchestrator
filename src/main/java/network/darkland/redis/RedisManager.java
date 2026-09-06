package network.darkland.redis;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.resilience.ResilienceConfig;
import network.darkland.resilience.ResilienceExecutor;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

public class RedisManager {

    public static final String CHANNEL = "darkland_nexus";
    private JedisPool pool;
    private final NexusApplication application;
    private final ResilienceConfig resilience;

    private final BlockingQueue<PendingMessage> messageQueue = new LinkedBlockingQueue<>(50000);

    private final ExecutorService mongoExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ExecutorService outboundExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
    );

    private final String consumerName;

    private static final int READ_BATCH_SIZE = 100;
    private static final int READ_BLOCK_MS = 5000;
    private static final long CLAIM_MIN_IDLE_MS = 30_000L;
    private static final int CLAIM_BATCH_SIZE = 100;
    private static final long CLAIM_SWEEP_INTERVAL_SECONDS = 15L;

    public static final String STREAM_KEY = "darkland_nexus_stream";

    public static final String STREAM_PAYLOAD_FIELD = "payload";

    public static final String CONSUMER_GROUP = "nexus-core-group";

    public record PendingMessage(StreamEntryID id, String payload) {}

    private volatile String claimCursor = "0-0";


    private final String redisHost;
    private final int redisPort;
    private final String redisUser;
    private final String redisPass;

    public RedisManager(NexusApplication application, String redisHost, int redisPort,
                        String redisUser, String redisPass, ResilienceConfig resilience) {
        this.application = application;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisUser = redisUser;
        this.redisPass = redisPass;
        this.resilience = resilience;
        this.consumerName = buildConsumerName();

        this.connect();

        this.startInboundWorkers();
        this.startStreamConsumer();
        this.startClaimSweeper();


        System.out.println("Nexus: System initialized with virtual-thread task execution.");
    }

    public RedisManager(NexusApplication application, String redisHost, int redisPort,
                        String redisUser, String redisPass) {
        this(application, redisHost, redisPort, redisUser, redisPass, new ResilienceConfig());
    }

    public RedisManager(NexusApplication application, String redisHost) {
        this(application, redisHost, 6379, null, null, new ResilienceConfig());
    }


    private static String buildConsumerName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return "nexus-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "nexus-" + UUID.randomUUID();
        }
    }

    private void startClaimSweeper() {
        scheduler.scheduleAtFixedRate(this::sweepIdlePendingEntries,
                CLAIM_SWEEP_INTERVAL_SECONDS, CLAIM_SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void startInboundWorkers() {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            new Thread(() -> {
                NexusReceiver receiver = new NexusReceiver(this);
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        PendingMessage pending = messageQueue.take();
                        try {
                            receiver.handleSyncMessage(pending.payload());
                        } finally {
                            acknowledge(pending.id());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "Nexus-Inbound-Worker-" + i).start();
        }
    }

    private void sweepIdlePendingEntries() {
        try (Jedis jedis = pool.getResource()) {
            XAutoClaimParams params = new XAutoClaimParams().count(CLAIM_BATCH_SIZE);

            Map.Entry<StreamEntryID, List<StreamEntry>> result = jedis.xautoclaim(
                    STREAM_KEY, CONSUMER_GROUP, consumerName,
                    CLAIM_MIN_IDLE_MS, new StreamEntryID(claimCursor), params);

            if (result == null) return;

            claimCursor = result.getKey().toString();

            List<StreamEntry> claimed = result.getValue();
            if (!claimed.isEmpty()) {
                System.out.println("Nexus: XAUTOCLAIM reclaimed " + claimed.size() + " idle pending entr"
                        + (claimed.size() == 1 ? "y" : "ies") + " for redelivery.");
            }

            for (StreamEntry entry : claimed) {
                enqueueEntry(entry);
            }
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                return;
            }
            System.err.println("Nexus: XAUTOCLAIM sweep failed: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Nexus: XAUTOCLAIM sweep failed: " + e.getMessage());
        }
    }


    public void startStreamConsumer() {
        scheduler.execute(() -> {
            ensureConsumerGroup();
            sweepIdlePendingEntries();

            System.out.println("Nexus: Consuming stream [" + STREAM_KEY + "] as consumer ["
                    + consumerName + "] in group [" + CONSUMER_GROUP + "]...");

            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = pool.getResource()) {
                    XReadGroupParams params = new XReadGroupParams()
                            .count(READ_BATCH_SIZE)
                            .block(READ_BLOCK_MS);

                    Map<String, StreamEntryID> streams = new LinkedHashMap<>();
                    streams.put(STREAM_KEY, StreamEntryID.UNRECEIVED_ENTRY);

                    List<Map.Entry<String, List<StreamEntry>>> result =
                            jedis.xreadGroup(CONSUMER_GROUP, consumerName, params, streams);

                    if (result == null) continue;

                    for (Map.Entry<String, List<StreamEntry>> streamResult : result) {
                        for (StreamEntry entry : streamResult.getValue()) {
                            enqueueEntry(entry);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Nexus: Stream read error, retrying in 5 seconds: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }

    public void acknowledge(StreamEntryID id) {
        processTask(() -> {
            try (Jedis jedis = pool.getResource()) {
                Pipeline pipeline = jedis.pipelined();
                pipeline.xack(STREAM_KEY, CONSUMER_GROUP, id);
                pipeline.xdel(STREAM_KEY, id);
                pipeline.sync();
            } catch (Exception e) {
                System.err.println("Nexus Error [XACK/XDEL]: " + id + " — " + e.getMessage());
            }
        });
    }


    private void enqueueEntry(StreamEntry entry) {
        Map<String, String> fields = entry.getFields();
        String payload = fields == null ? null : fields.get(STREAM_PAYLOAD_FIELD);

        if (payload == null) {
            System.err.println("Nexus: Stream entry " + entry.getID() + " has no '" + STREAM_PAYLOAD_FIELD + "' field, skipping.");
            acknowledge(entry.getID());
            return;
        }

        if (!messageQueue.offer(new RedisManager.PendingMessage(entry.getID(), payload))) {
            System.err.println("Nexus: Inbound queue full, deferring entry " + entry.getID() + " to the next XAUTOCLAIM sweep.");
        }
    }

    public void processTask(Runnable task) {
        outboundExecutor.execute(task);
    }

    public void processMongoTask(Runnable task) {
        mongoExecutor.execute(task);
    }

    public ExecutorService getMongoExecutor() {
        return mongoExecutor;
    }

    public void connect() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(64);
        poolConfig.setMinIdle(16);

        boolean hasAuth = redisPass != null && !redisPass.isBlank();
        boolean hasUser = redisUser != null && !redisUser.isBlank();

        if (hasAuth) {
            this.pool = new JedisPool(
                    poolConfig,
                    this.redisHost,
                    this.redisPort,
                    2000,
                    hasUser ? redisUser : null,
                    redisPass
            );
            System.out.println("Nexus: Connection pool created for host: " + this.redisHost
                    + ":" + this.redisPort + " (auth ENABLED)");
        } else {
            this.pool = new JedisPool(poolConfig, this.redisHost, this.redisPort);
            System.err.println("Nexus: WARNING — Connecting to Redis without a password! "
                    + "Make sure to configure the 'redisPass' setting in the production environment.");
        }

        enableKeyspaceNotifications();
    }

    public void enableKeyspaceNotifications() {
        try (Jedis jedis = pool.getResource()) {
            jedis.configSet("notify-keyspace-events", "Ex");
            System.out.println("Nexus: Keyspace Notifications (Expired) enabled via Jedis.");
        } catch (Exception e) {
            System.err.println("Nexus: Failed to set Redis config! Yetki sorunu olabilir.");
        }
    }


    private void ensureConsumerGroup() {
        try (Jedis jedis = pool.getResource()) {
            jedis.xgroupCreate(STREAM_KEY, CONSUMER_GROUP, StreamEntryID.LAST_ENTRY, true);
            System.out.println("Nexus: Consumer group [" + CONSUMER_GROUP + "] ready on stream [" + STREAM_KEY + "].");
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                System.out.println("Nexus: Consumer group [" + CONSUMER_GROUP + "] already exists, reusing it.");
            } else {
                System.err.println("Nexus: Failed to create/verify consumer group: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Nexus: Unexpected error ensuring consumer group: " + e.getMessage());
        }
    }

    public void scheduleTask(Runnable task, long initialDelay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    public void renewTTL(String key, int seconds) {
        processTask(() -> ResilienceExecutor.decorateSyncVoid(
                resilience.redisCircuitBreaker(),
                resilience.redisRetry(),
                "EXPIRE " + key,
                () -> {
                    try (Jedis jedis = pool.getResource()) {
                        jedis.expire(key, seconds);
                    }
                }
        ));
    }

    public void setData(String key, String json, DataAddon addon) {
        processTask(() -> ResilienceExecutor.decorateSyncVoid(
                resilience.redisCircuitBreaker(),
                resilience.redisRetry(),
                "SET " + key,
                () -> {
                    try (Jedis jedis = pool.getResource()) {
                        SetParams params = new SetParams().ex(addon.getCacheTTL());
                        jedis.set(key, json, params);
                    }
                }
        ));
    }

    public Optional<String> getData(String key) {
        return ResilienceExecutor.decorateSync(
                resilience.redisCircuitBreaker(),
                resilience.redisRetry(),
                "GET " + key,
                () -> {
                    try (Jedis jedis = pool.getResource()) {
                        return Optional.ofNullable(jedis.get(key));
                    }
                },
                Optional::empty // fallback: cache miss gibi davran, Mongo'ya düşsün
        );
    }

    public boolean exists(String key) {
        return ResilienceExecutor.decorateSync(
                resilience.redisCircuitBreaker(),
                resilience.redisRetry(),
                "EXISTS " + key,
                () -> {
                    try (Jedis jedis = pool.getResource()) {
                        return jedis.exists(key);
                    }
                },
                () -> false // fallback: yok say, Mongo'dan doğrulansın
        );
    }

    public void deleteData(String key) {
        processTask(() -> ResilienceExecutor.decorateSyncVoid(
                resilience.redisCircuitBreaker(),
                resilience.redisRetry(),
                "DEL " + key,
                () -> {
                    try (Jedis jedis = pool.getResource()) {
                        jedis.del(key);
                    }
                }
        ));
    }

    public void publish(String channel, String message) {
        processTask(() -> ResilienceExecutor.decorateSyncVoid(
                resilience.redisCircuitBreaker(),
                resilience.redisRetry(),
                "PUBLISH " + channel,
                () -> {
                    try (Jedis jedis = pool.getResource()) {
                        jedis.publish(channel, message);
                    }
                }
        ));
    }

    public void shutdown() {
        scheduler.shutdown();
        mongoExecutor.shutdown();
        outboundExecutor.shutdown();
        resilience.shutdown();
        pool.close();
    }

    public NexusApplication getApplication() {
        return application;
    }

    public JedisPool getPool() {
        return pool;
    }

    public ResilienceConfig getResilience() {
        return resilience;
    }
}