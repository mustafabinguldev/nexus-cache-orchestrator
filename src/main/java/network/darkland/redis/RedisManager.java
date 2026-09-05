package network.darkland.redis;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.util.Optional;
import java.util.concurrent.*;

public class RedisManager {

    public static final String CHANNEL = "darkland_nexus";
    private JedisPool pool;
    private final NexusApplication application;

    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(50000);

    private final ExecutorService mongoExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ExecutorService outboundExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
    );

    private final String redisHost;
    private final int redisPort;
    private final String redisUser;
    private final String redisPass;

    public RedisManager(NexusApplication application, String redisHost, int redisPort,
                        String redisUser, String redisPass) {
        this.application = application;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisUser = redisUser;
        this.redisPass = redisPass;

        this.connect();

        this.startInboundWorkers();
        this.startListening();
        System.out.println("Nexus: System initialized with virtual-thread task execution.");
    }

    public RedisManager(NexusApplication application, String redisHost) {
        this(application, redisHost, 6379, null, null);
    }

    private void startInboundWorkers() {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            new Thread(() -> {
                NexusReceiver receiver = new NexusReceiver(this);
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String msg = messageQueue.take();
                        receiver.handleSyncMessage(msg);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "Nexus-Inbound-Worker-" + i).start();
        }
    }

    public void enqueueMessage(String message) {
        messageQueue.offer(message);
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

    public void startListening() {
        scheduler.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (var jedis = pool.getResource()) {
                    System.out.println("Nexus: Listening on channel [" + CHANNEL + "]...");
                    jedis.subscribe(new NexusReceiver(this), CHANNEL);
                } catch (Exception e) {
                    System.err.println("Nexus: Redis connection lost! Retrying in 5 seconds...");
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }

    public void scheduleTask(Runnable task, long initialDelay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    public void renewTTL(String key, int seconds) {
        processTask(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.expire(key, seconds);
            } catch (Exception e) {
                System.err.println("Nexus Error [EXPIRE]: " + key);
            }
        });
    }

    public void setData(String key, String json, DataAddon addon) {
        processTask(() -> {
            try (Jedis jedis = pool.getResource()) {
                SetParams params = new SetParams().ex(addon.getCacheTTL());
                jedis.set(key, json, params);
            } catch (Exception e) {
                System.err.println("Nexus Error [SET]: " + key);
            }
        });
    }

    public Optional<String> getData(String key) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(key);
            return Optional.ofNullable(val);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean exists(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(key);
        } catch (Exception e) {
            return false;
        }
    }

    public void deleteData(String key) {
        processTask(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.del(key);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void publish(String channel, String message) {
        processTask(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(channel, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void shutdown() {
        scheduler.shutdown();
        mongoExecutor.shutdown();
        outboundExecutor.shutdown(); // YENİ — eklenmezse virtual thread executor kapanışta temiz sonlanmaz
        pool.close();
    }

    public NexusApplication getApplication() {
        return application;
    }

    public JedisPool getPool() {
        return pool;
    }
}