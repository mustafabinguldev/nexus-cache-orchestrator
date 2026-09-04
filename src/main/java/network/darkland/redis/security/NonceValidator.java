package network.darkland.redis.security;

import network.darkland.protocol.NexusJsonDataContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NonceValidator implements MessageValidator {

    private static final String FIELD_NONCE = "nonce";

    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

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
        usedNonces.entrySet().removeIf(entry -> entry.getValue() < expirationTime);
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

            Long previous = usedNonces.putIfAbsent(nonce, System.currentTimeMillis());
            if (previous != null) {
                return ValidationResult.reject("nonce reused: " + nonce);
            }
            return ValidationResult.ok();

        } catch (Exception e) {
            return ValidationResult.reject("Error in nonce check: " + e.getMessage());
        }
    }
}