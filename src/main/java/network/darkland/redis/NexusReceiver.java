package network.darkland.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.util.JsonUtils;
import redis.clients.jedis.JedisPubSub;

import java.util.Optional;

public class NexusReceiver extends JedisPubSub {

    private RedisManager redisManager;
    public NexusReceiver(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    @Override
    public void onMessage(String channel, String message) {
        redisManager.enqueueMessage(message);
    }

    public void handleSyncMessage(String message) {
        try {
            NexusJsonDataContainer dataContainer = new NexusJsonDataContainer(message);

            if (!dataContainer.containsKey("type")) {
                return;
            }

            String typeStr = dataContainer.get("type", String.class);
            DataAddon.RequestType type;
            try {
                type = DataAddon.RequestType.valueOf(typeStr);
            } catch (Exception exception) {
                return;
            }

            if (type.equals(DataAddon.RequestType.LOAD_CACHE)) {
                if (!dataContainer.containsKey("protocol") || !dataContainer.containsKey("key")) {
                    return;
                }
                int protocol = dataContainer.get("protocol", Integer.class);
                String key = dataContainer.get("key", String.class);
                Optional<DataAddon> addon = NexusApplication.getApplication().getProtocolHandler().getAddonById(protocol);
                if (addon.isPresent()) {
                    addon.get().loadIntoCache(key);
                }
                return;
            }

            if (!dataContainer.containsKey("protocol") || !dataContainer.containsKey("source")) {
                return;
            }
            if (type.equals(DataAddon.RequestType.BROADCAST)) {
                return;
            }
            if (!dataContainer.containsKey("data")) {
                return;
            }

            int protocolId;
            try {
                protocolId = dataContainer.get("protocol", Integer.class);
            } catch (Exception exception) {
                return;
            }
            String source = dataContainer.get("source", String.class);
            Object dataObj = dataContainer.get("data", Object.class);
            String jsonData;

            if (dataObj instanceof String) {
                jsonData = (String) dataObj;
            } else {
                jsonData = JsonUtils.getMapper().writeValueAsString(dataObj);
            }

            NexusJsonDataContainer requestDataContainer;
            try {
                requestDataContainer = new NexusJsonDataContainer(jsonData);
                Optional<DataAddon> addon = redisManager.getApplication().getProtocolHandler().getAddonById(protocolId);
                if (addon.isEmpty()) {
                    return;
                }
                if (addon.get().handleRequest(source, type, requestDataContainer)) {
                    switch (type) {
                        case GET_DATA -> addon.get().handleGet(source, requestDataContainer);
                        case SET_DATA -> addon.get().handleSet(source, requestDataContainer);
                        case REMOVE_DATA -> addon.get().handleRemove(source, requestDataContainer);
                    }
                }
            } catch (Exception exception) {
                return;
            }

        } catch (JsonProcessingException e) {
            return;
        }
    }
}