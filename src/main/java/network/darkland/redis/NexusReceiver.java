package network.darkland.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.util.JsonUtils;
import redis.clients.jedis.JedisPubSub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NexusReceiver extends JedisPubSub {

    private static final Logger LOGGER = Logger.getLogger(NexusReceiver.class.getName());

    private static final String FIELD_TYPE     = "type";
    private static final String FIELD_PROTOCOL = "protocol";
    private static final String FIELD_KEY      = "key";
    private static final String FIELD_SOURCE   = "source";
    private static final String FIELD_DATA     = "data";
    private static final String FIELD_SIG      = "sig";
    private static final String FIELD_NONCE = "nonce";
    private static final String FIELD_TIMESTAMP = "timestamp";


    private static final String SHARED_SECRET =
            System.getenv().getOrDefault("NEXUS_SIGNING_KEY", "");

    private static final long TIMESTAMP_WINDOW_MILLIS = 5 * 60 * 1000L;


    private static volatile boolean warnedOnce = false;

    private static final Map<String, Long> USED_NONCES =
            new ConcurrentHashMap<>();

    private final RedisManager redisManager;

    public NexusReceiver(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    @Override
    public void onMessage(String channel, String message) {
        redisManager.enqueueMessage(message);
    }

    private void cleanupExpiredNonces(long now) {

        long expirationTime =
                now - TIMESTAMP_WINDOW_MILLIS;

        USED_NONCES.entrySet().removeIf(
                entry -> entry.getValue() < expirationTime
        );
    }

    private boolean isNonceValid(NexusJsonDataContainer container) {

        if (!container.containsKey(FIELD_NONCE)) {
            LOGGER.warning("Nonce alanı eksik, mesaj reddedildi.");
            return false;
        }

        try {
            String nonce =
                    container.get(FIELD_NONCE, String.class);

            if (nonce == null || nonce.isBlank()) {
                LOGGER.warning("Nonce boş, mesaj reddedildi.");
                return false;
            }

            long now = System.currentTimeMillis();

            cleanupExpiredNonces(now);

            Long previous =
                    USED_NONCES.putIfAbsent(nonce, now);

            if (previous != null) {
                LOGGER.warning(
                        "Tekrar kullanılan nonce tespit edildi: "
                                + nonce
                );

                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.warning(
                    "Nonce kontrolünde hata, mesaj reddedildi: "
                            + e.getMessage()
            );

            return false;
        }
    }

    private boolean isTimestampValid(NexusJsonDataContainer container) {

        if (!container.containsKey(FIELD_TIMESTAMP)) {
            LOGGER.warning("Timestamp alanı eksik, mesaj reddedildi.");
            return false;
        }

        try {
            long timestamp =
                    container.get(FIELD_TIMESTAMP, Long.class);

            long now = System.currentTimeMillis();

            long difference = Math.abs(now - timestamp);

            if (difference > TIMESTAMP_WINDOW_MILLIS) {
                LOGGER.warning(
                        "Mesaj timestamp sınırının dışında. Fark: "
                                + difference
                                + " ms"
                );

                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.warning(
                    "Timestamp okunamadı, mesaj reddedildi."
            );

            return false;
        }
    }

    private boolean isSignatureValid(NexusJsonDataContainer container) {
        if (SHARED_SECRET.isBlank()) {
            if (!warnedOnce) {
                warnedOnce = true;
                LOGGER.warning("NEXUS_SIGNING_KEY ayarlanmamış — mesaj imza doğrulaması DEVRE DIŞI. "
                        + "Bu sadece geçiş dönemi için kabul edilebilir, üretimde mutlaka ayarlayın.");
            }
            return true;
        }

        if (!container.containsKey(FIELD_SIG)) {
            LOGGER.warning("İmza alanı ('sig') eksik, mesaj reddedildi.");
            return false;
        }

        try {
            String providedSig = container.get(FIELD_SIG, String.class);

            NexusJsonDataContainer withoutSig = new NexusJsonDataContainer(container.toFullJson());
            withoutSig.remove(FIELD_SIG);

            String expectedSig = hmacSha256(withoutSig.toFullJson(), SHARED_SECRET);
            boolean valid = expectedSig.equals(providedSig);

            if (!valid) {
                LOGGER.warning("İmza doğrulaması başarısız — mesaj reddedildi. "
                        + "Gönderen taraf yanlış/eski anahtar kullanıyor olabilir.");
            }
            return valid;
        } catch (JsonProcessingException e) {
            LOGGER.warning("İmza doğrulaması sırasında JSON hatası, mesaj reddedildi: " + e.getMessage());
            return false;
        }
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC hesaplanamadı", e);
        }
    }

    public void handleSyncMessage(String message) {
        try {
            NexusJsonDataContainer dataContainer = new NexusJsonDataContainer(message);

            if (!isSignatureValid(dataContainer)) {
                return;
            }

            if (!isTimestampValid(dataContainer)) {
                return;
            }

            if (!isNonceValid(dataContainer)) {
                return;
            }

            if (!dataContainer.containsKey(FIELD_TYPE)) {
                LOGGER.fine("Incoming message is missing the 'type' field, skipping.");
                return;
            }

            String typeStr = dataContainer.get(FIELD_TYPE, String.class);
            DataAddon.RequestType type;
            try {
                type = DataAddon.RequestType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Unknown RequestType received: " + typeStr);
                return;
            }

            if (type == DataAddon.RequestType.LOAD_CACHE) {
                handleLoadCache(dataContainer);
                return;
            }

            if (!dataContainer.containsKey(FIELD_PROTOCOL) || !dataContainer.containsKey(FIELD_SOURCE)) {
                LOGGER.fine("Message is missing required 'protocol' or 'source' field, skipping.");
                return;
            }

            if (type == DataAddon.RequestType.BROADCAST) {
                handleBroadcast(dataContainer);
                return;
            }

            if (!dataContainer.containsKey(FIELD_DATA)) {
                LOGGER.fine("Message is missing the 'data' field, skipping.");
                return;
            }

            int protocolId;
            try {
                protocolId = dataContainer.get(FIELD_PROTOCOL, Integer.class);
            } catch (Exception e) {
                LOGGER.warning("Failed to parse 'protocol' field as integer.");
                return;
            }

            String source  = dataContainer.get(FIELD_SOURCE, String.class);
            Object dataObj = dataContainer.get(FIELD_DATA, Object.class);

            if (dataObj == null) {
                LOGGER.fine("'data' field is null, skipping.");
                return;
            }

            String jsonData;
            if (dataObj instanceof String s) {
                jsonData = s;
            } else {
                jsonData = JsonUtils.getMapper().writeValueAsString(dataObj);
            }

            Optional<DataAddon> addonOpt = redisManager.getApplication()
                    .getProtocolHandler()
                    .getAddonById(protocolId);

            if (addonOpt.isEmpty()) {
                LOGGER.fine("No addon found for protocol ID: " + protocolId);
                return;
            }

            DataAddon addon = addonOpt.get();
            NexusJsonDataContainer requestData = new NexusJsonDataContainer(jsonData);

            if (!addon.handleRequest(source, type, requestData)) {
                return;
            }

            switch (type) {
                case GET_DATA       -> addon.handleGet(source, requestData);
                case SET_DATA       -> addon.handleSet(source, requestData);
                case REMOVE_DATA    -> addon.handleRemove(source, requestData);
                case INCREMENT_DATA -> addon.handleIncrementData(source, requestData);
                case RANKING        -> addon.handleRankingData(source, requestData);
                case RANK_FINDER    -> addon.handleRankFinderData(source, requestData);
                default             -> LOGGER.warning("Unhandled RequestType: " + type);
            }

        } catch (JsonProcessingException e) {
            LOGGER.log(Level.SEVERE, "JSON processing error: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error while handling sync message: " + e.getMessage(), e);
        }
    }

    private void handleLoadCache(NexusJsonDataContainer dataContainer) {
        if (!dataContainer.containsKey(FIELD_PROTOCOL) || !dataContainer.containsKey(FIELD_KEY)) {
            LOGGER.fine("LOAD_CACHE: Missing required 'protocol' or 'key' field.");
            return;
        }

        int protocol = dataContainer.get(FIELD_PROTOCOL, Integer.class);
        String key   = dataContainer.get(FIELD_KEY, String.class);

        Optional<DataAddon> addonOpt = NexusApplication.getApplication()
                .getProtocolHandler()
                .getAddonById(protocol);

        if (addonOpt.isPresent()) {
            addonOpt.get().loadIntoCache(key);
        } else {
            LOGGER.fine("LOAD_CACHE: No addon found for protocol ID: " + protocol);
        }
    }

    private void handleBroadcast(NexusJsonDataContainer dataContainer) {
        LOGGER.fine("BROADCAST message received, no handler implemented yet.");
    }
}