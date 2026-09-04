package network.darkland.redis.security;

import network.darkland.protocol.NexusJsonDataContainer;

public class TimestampValidator implements MessageValidator {

    private static final String FIELD_TIMESTAMP = "timestamp";

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        if (!message.containsKey(FIELD_TIMESTAMP)) {
            return ValidationResult.reject("The timestamp field is missing.");
        }

        try {
            long timestamp = message.get(FIELD_TIMESTAMP, Long.class);
            long now = System.currentTimeMillis();
            long difference = Math.abs(now - timestamp);

            if (difference > NexusSecurityConfig.TIMESTAMP_WINDOW_MILLIS) {
                return ValidationResult.reject("timestamp outside the window (difference: " + difference + " ms)");
            }
            return ValidationResult.ok();

        } catch (Exception e) {
            return ValidationResult.reject("Timestamp could not be read.: " + e.getMessage());
        }
    }
}