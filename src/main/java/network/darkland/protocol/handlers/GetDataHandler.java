package network.darkland.protocol.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestHandler;
import network.darkland.redis.MessageAuth;
import network.darkland.redis.RedisManager;
import network.darkland.util.JsonUtils;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GetDataHandler implements RequestHandler {

    private static final Logger LOGGER = Logger.getLogger(GetDataHandler.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void handle(DataAddon addon, String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            try {
                Optional<DataModel> dataModelOpt = addon.getData(json);
                DataModel targetModel;

                if (dataModelOpt.isEmpty()) {
                    String idFieldName = addon.getIdFieldName();
                    if (idFieldName.isEmpty()) return;

                    NexusJsonDataContainer extract = json.containsKey("data")
                            ? new NexusJsonDataContainer(MAPPER.writeValueAsString(json.get("data", Object.class)))
                            : json;

                    Object idValue = extract.get(idFieldName, Object.class);
                    if (idValue == null) return;

                    targetModel = addon.createModel(addon.generateRawJson(idValue.toString()));
                    app.getDataContainer().addModelDirect(targetModel.getKey(), targetModel);
                } else {
                    targetModel = dataModelOpt.get();
                }

                ObjectNode rootNode = JsonUtils.getMapper().createObjectNode();
                rootNode.put("protocol", addon.addonId());
                rootNode.put("source",   "nexus");
                rootNode.put("type",     "BROADCAST");
                rootNode.put("target",   source);
                rootNode.set("data",     MAPPER.readTree(targetModel.getValueJson()));

                app.getRedisManager().publish(
                        RedisManager.CHANNEL + "_" + source,
                        MessageAuth.stamp(MAPPER.writeValueAsString(rootNode))
                );

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[GetDataHandler/" + addon.addonName() + "] hata", e);
            }
        });
    }
}
