package network.darkland.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;

public class MessageAuth {

    private static final Logger LOGGER = Logger.getLogger(MessageAuth.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SHARED_SECRET =
            System.getenv().getOrDefault("NEXUS_SIGNING_KEY", "");

    private static volatile boolean warnedOnce = false;

    public static boolean isEnabled() {
        return !SHARED_SECRET.isBlank();
    }

    public static String stamp(String json) {
        if (!isEnabled()) {
            if (!warnedOnce) {
                warnedOnce = true;
                LOGGER.warning("NEXUS_SIGNING_KEY is not set — OUTGOING messages are NOT SIGNED. "
                        + "In this case, someone with publish permissions for Redis could impersonate Nexus and... "
                        + "It can send fake data to servers. Be sure to configure this in production..");
            }
            return json;
        }

        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(json);
            node.put("timestamp", System.currentTimeMillis());
            node.put("nonce", UUID.randomUUID().toString());

            String unsigned = MAPPER.writeValueAsString(node);
            String sig = hmacSha256(unsigned, SHARED_SECRET);
            node.put("sig", sig);

            return MAPPER.writeValueAsString(node);

        } catch (Exception e) {
            LOGGER.warning("The outgoing message could not be signed; it is being sent unsigned.: " + e.getMessage());
            return json;
        }
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC could not be calculated.", e);
        }
    }
}