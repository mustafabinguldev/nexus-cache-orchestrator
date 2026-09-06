package network.darkland.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestType;
import network.darkland.redis.security.MessageValidationChain;
import network.darkland.redis.security.NonceValidator;
import network.darkland.redis.security.SignatureValidator;
import network.darkland.redis.security.TimestampValidator;
import network.darkland.util.JsonUtils;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NexusReceiver {

    private static final Logger LOGGER = Logger.getLogger(NexusReceiver.class.getName());

    private static final String FIELD_TYPE     = "type";
    private static final String FIELD_PROTOCOL = "protocol";
    private static final String FIELD_KEY      = "key";
    private static final String FIELD_SOURCE   = "source";
    private static final String FIELD_DATA     = "data";

    private static final MessageValidationChain VALIDATION_CHAIN = new MessageValidationChain(
            List.of(
                    new SignatureValidator(),
                    new TimestampValidator(),
                    new NonceValidator()
            )
    );

    private final RedisManager redisManager;

    public NexusReceiver(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void handleSyncMessage(String message) {
        try {
            NexusJsonDataContainer dataContainer = new NexusJsonDataContainer(message);

            if (!VALIDATION_CHAIN.runAll(dataContainer)) {
                return;
            }

            if (!dataContainer.containsKey(FIELD_TYPE)) {
                LOGGER.fine("Incoming message is missing the 'type' field, skipping.");
                return;
            }

            String typeStr = dataContainer.get(FIELD_TYPE, String.class);

            Optional<RequestType> typeOpt = RequestType.lookup(typeStr);
            if (typeOpt.isEmpty()) {
                LOGGER.warning("Unknown RequestType received: " + typeStr);
                return;
            }
            RequestType type = typeOpt.get();

            if (type == RequestType.LOAD_CACHE) {
                handleLoadCache(dataContainer);
                return;
            }

            if (!dataContainer.containsKey(FIELD_PROTOCOL) || !dataContainer.containsKey(FIELD_SOURCE)) {
                LOGGER.fine("Message is missing required 'protocol' or 'source' field, skipping.");
                return;
            }

            if (type == RequestType.BROADCAST) {
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

            if (!addon.getAdditionalValidationChain().runAll(requestData)) {
                return;
            }

            if (!addon.handleRequest(source, type, requestData)) {
                return;
            }

            addon.dispatch(source, type, requestData);

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