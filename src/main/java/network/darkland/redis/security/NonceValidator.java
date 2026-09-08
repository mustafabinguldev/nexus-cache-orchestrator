package network.darkland.redis.security;

import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.redis.RedisManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NonceValidator implements MessageValidator {

    private static final String FIELD_NONCE = "nonce";

    private record Seen(long time, String deliveryId) {}
    private final Map<String, Seen> usedNonces = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Nexus-Nonce-Cleanup");
                t.setDaemon(true);
                return t;
            });

    public NonceValidator() {
        long window = NexusSecurityConfig.TIMESTAMP_WINDOW_MILLIS;
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpired, window, window, TimeUnit.MILLISECONDS);
    }

    private void cleanupExpired() {
        long expirationTime = System.currentTimeMillis() - NexusSecurityConfig.TIMESTAMP_WINDOW_MILLIS;
        usedNonces.entrySet().removeIf(entry -> entry.getValue().time() < expirationTime);
    }

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        if (!message.containsKey(FIELD_NONCE)) {
            return ValidationResult.reject("nonce field is missing");
        }

        try {
            String nonce = message.get(FIELD_NONCE, String.class);
            if (nonce == null || nonce.isBlank()) {
                return ValidationResult.reject("nonce is empty");
            }

            String deliveryId = RedisManager.currentDeliveryId();
            Seen previous = usedNonces.putIfAbsent(nonce, new Seen(System.currentTimeMillis(), deliveryId));
            if (previous != null && (deliveryId == null || !deliveryId.equals(previous.deliveryId()))) {
                return ValidationResult.reject("nonce reused: " + nonce);
            }
            return ValidationResult.ok();

        } catch (Exception e) {
            return ValidationResult.reject("Error in nonce check: " + e.getMessage());
        }
    }
}
