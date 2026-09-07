package network.darkland.cache;

import network.darkland.NexusApplication;
import network.darkland.redis.RedisManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.logging.Logger;

public final class L1InvalidationBus {

    private static final Logger LOGGER = Logger.getLogger(L1InvalidationBus.class.getName());
    private static final String CHANNEL = "darkland_nexus_l1invalidate";

    private static final boolean CLUSTER_MODE = Boolean.parseBoolean(
            System.getenv().getOrDefault("NEXUS_CLUSTER_MODE", "false"));

    public static boolean isClusterModeEnabled() {
        return CLUSTER_MODE;
    }

    private final String nodeId = UUID.randomUUID().toString();
    private final RemoveCallback removeCallback;

    public interface RemoveCallback {
        void removeLocal(String key);
    }

    public L1InvalidationBus(RemoveCallback removeCallback) {
        this.removeCallback = removeCallback;
    }

    public void start() {
        if (!CLUSTER_MODE) {
            LOGGER.info("[L1InvalidationBus] CLUSTER_MODE disabled (single instance) — subscriber not started.");
            return;
        }
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = NexusApplication.getApplication().getRedisManager().getPool().getResource()) {
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            int sep = message.indexOf('|');
                            if (sep < 0) return;

                            String senderId = message.substring(0, sep);
                            String key = message.substring(sep + 1);

                            if (nodeId.equals(senderId)) {
                                return;
                            }
                            removeCallback.removeLocal(key);
                        }
                    }, CHANNEL);
                } catch (Exception e) {
                    LOGGER.warning("[L1InvalidationBus] Subscription lost; will retry in 5 seconds: " + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "Nexus-L1-Invalidation-Bus").start();
    }
    public void publishInvalidation(String key) {
        if (!CLUSTER_MODE) return;
        RedisManager rm = NexusApplication.getApplication().getRedisManager();
        rm.publish(CHANNEL, nodeId + "|" + key);
    }
}