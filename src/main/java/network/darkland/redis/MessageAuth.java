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

    private static volatile boolean warnedOnce = false;

    public static String stamp(String json) {
        if (!NexusSecurityConfig.isSigningEnabled()) {
            return json;
        }
        try {
            Map<String, Object> canonical = new TreeMap<>(
                    MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {})
            );
            canonical.put("timestamp", System.currentTimeMillis());
            canonical.put("nonce", UUID.randomUUID().toString());

            String unsigned = MAPPER.writeValueAsString(canonical); // artık alfabetik sıralı
            String sig = HmacSigner.sign(unsigned, NexusSecurityConfig.SHARED_SECRET);

            canonical.put("sig", sig);
            return MAPPER.writeValueAsString(canonical);

        } catch (Exception e) {
            LOGGER.warning("The outgoing message could not be signed; it is being sent unsigned.: " + e.getMessage());
            return json;
        }
    }

    private MessageAuth() {
    }
}