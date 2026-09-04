package network.darkland.redis.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import network.darkland.protocol.NexusJsonDataContainer;

import java.util.logging.Logger;

public class SignatureValidator implements MessageValidator {

    private static final Logger LOGGER = Logger.getLogger(SignatureValidator.class.getName());
    private static final String FIELD_SIG = "sig";

    private volatile boolean warnedOnce = false;

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        if (!NexusSecurityConfig.isSigningEnabled()) {
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

            if (!expectedSig.equals(providedSig)) {
                return ValidationResult.reject("Signature does not match (key might be incorrect or outdated)");
            }
            return ValidationResult.ok();

        } catch (JsonProcessingException e) {
            return ValidationResult.reject("JSON error while verifying signature: " + e.getMessage());
        }
    }
}