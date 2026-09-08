package network.darkland.redis.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import network.darkland.protocol.NexusJsonDataContainer;

import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class SignatureValidator implements MessageValidator {

    private static final Logger LOGGER = Logger.getLogger(SignatureValidator.class.getName());
    private static final String FIELD_SIG = "sig";

    private volatile boolean warnedOnce = false;

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        if (!NexusSecurityConfig.isSigningEnabled()) {
            if (!NexusSecurityConfig.ALLOW_UNSIGNED_MESSAGES) {
                return ValidationResult.reject("NEXUS_SIGNING_KEY is not configured; unsigned messages are disabled");
            }
            if (!warnedOnce) {
                warnedOnce = true;
                LOGGER.warning("NEXUS_SIGNING_KEY is not set — signature verification is DISABLED. "
                        + "This is acceptable only for the transition period; be sure to make adjustments in production.");
            }
            return ValidationResult.ok();
        }

        if (!message.containsKey(FIELD_SIG)) {
            return ValidationResult.reject("Signature field ('sig') missing");
        }

        try {
            String providedSig = message.get(FIELD_SIG, String.class);

            NexusJsonDataContainer withoutSig = new NexusJsonDataContainer(message.toFullJson());
            withoutSig.remove(FIELD_SIG);

            String expectedSig = HmacSigner.sign(withoutSig.toFullJson(), NexusSecurityConfig.SHARED_SECRET);

            if (providedSig == null || !MessageDigest.isEqual(
                    expectedSig.getBytes(StandardCharsets.UTF_8), providedSig.getBytes(StandardCharsets.UTF_8))) {
                return ValidationResult.reject("Signature does not match (key might be incorrect or outdated)");
            }
            return ValidationResult.ok();

        } catch (JsonProcessingException e) {
            return ValidationResult.reject("JSON error while verifying signature: " + e.getMessage());
        }
    }
}
