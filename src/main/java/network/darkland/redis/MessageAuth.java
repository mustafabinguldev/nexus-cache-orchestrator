package network.darkland.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import network.darkland.redis.security.HmacSigner;
import network.darkland.redis.security.NexusSecurityConfig;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;


public class MessageAuth {

    private static final Logger LOGGER = Logger.getLogger(MessageAuth.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String stamp(String json) {
        try {
            Map<String, Object> canonical = new TreeMap<>(
                    MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {})
            );
            canonical.put("timestamp", System.currentTimeMillis());
            canonical.put("nonce", UUID.randomUUID().toString());
            canonical.remove("sig");

            if (NexusSecurityConfig.isSigningEnabled()) {
                String unsigned = MAPPER.writeValueAsString(canonical);
                canonical.put("sig", HmacSigner.sign(unsigned, NexusSecurityConfig.SHARED_SECRET));
            }
            return MAPPER.writeValueAsString(canonical);

        } catch (Exception e) {
            LOGGER.warning("Could not stamp outgoing message: " + e.getMessage());
            throw new IllegalStateException("Could not stamp outgoing message", e);
        }
    }

    private MessageAuth() {
    }
}
