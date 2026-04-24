package network.darkland.redis;

import network.darkland.NexusApplication;
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

    private final BlockingQueue<Runnable> internalTaskQueue = new LinkedBlockingQueue<>(50000);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
    );
    private String redisHost;

    public RedisManager(NexusApplication application, String redisHost) {
        this.application = application;
        this.redisHost = redisHost;
        this.connect();

        this.startInboundWorkers();
        this.startOutboundWorkers();

        this.startListening();
        System.out.println("Nexus: System initialized with high-performance dual-queue logic.");
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

    private void startOutboundWorkers() {
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        for (int i = 0; i < workers; i++) {
            new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Runnable task = internalTaskQueue.take();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, "Nexus-Outbound-Worker-" + i).start();
        }
    }

    public void enqueueMessage(String message) {
        messageQueue.offer(message);
    }

    public void processTask(Runnable task) {
        if (!internalTaskQueue.offer(task)) {
            scheduler.execute(task);
        }
    }

    public void connect() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(64);
        poolConfig.setMinIdle(16);
        this.pool = new JedisPool(poolConfig, this.redisHost, 6379);
        System.out.println("Nexus: Connection pool created for host: " + this.redisHost);
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

    public void setData(String key, String json) {
        processTask(() -> {
            try (Jedis jedis = pool.getResource()) {
                SetParams params = new SetParams().ex(600);
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
        pool.close();
    }

    public NexusApplication getApplication() {
        return application;
    }
}