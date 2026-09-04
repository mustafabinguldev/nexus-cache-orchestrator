package network.darkland.redis.security;

import network.darkland.protocol.NexusJsonDataContainer;

public interface MessageValidator {

    ValidationResult validate(NexusJsonDataContainer message);
}