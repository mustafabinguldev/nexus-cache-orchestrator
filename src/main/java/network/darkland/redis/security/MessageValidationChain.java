package network.darkland.redis.security;

import network.darkland.protocol.NexusJsonDataContainer;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MessageValidationChain {

    private static final Logger LOGGER = Logger.getLogger(MessageValidationChain.class.getName());

    private final List<MessageValidator> validators;

    public MessageValidationChain(List<MessageValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    public boolean runAll(NexusJsonDataContainer message) {
        for (MessageValidator validator : validators) {
            ValidationResult result = validator.validate(message);

            if (!result.valid()) {
                LOGGER.log(Level.WARNING, "[{0}] Message rejected: {1}",
                        new Object[]{validator.getClass().getSimpleName(), result.reason()});
                return false;
            }
        }
        return true;
    }
}