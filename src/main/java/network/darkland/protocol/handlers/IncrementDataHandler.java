package network.darkland.protocol.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestHandler;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IncrementDataHandler implements RequestHandler {

    private static final Logger LOGGER = Logger.getLogger(IncrementDataHandler.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void handle(DataAddon addon, String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            String lockKey = null;
            Object lock = null;
            try {
                if (!json.containsKey("key") || !json.containsKey("field") || !json.containsKey("amount")) {
                    LOGGER.warning("[IncrementDataHandler/" + addon.addonName() + "] eksik alan (key/field/amount)");
                    return;
                }

                String field    = json.get("field",  String.class);
                Number amount   = json.get("amount", Number.class);
                Object keyValue = json.get("key",    addon.getIdClassName());

                if (field == null || amount == null || keyValue == null) {
                    LOGGER.warning("[IncrementDataHandler/" + addon.addonName() + "] null değer tespit edildi");
                    return;
                }

                json.set(addon.getIdFieldName(), keyValue);

                // Per-key lock — aynı key için paralel increment'i engeller.
                lockKey = keyValue.toString();
                lock = addon.acquireKeyLock(lockKey);

                synchronized (lock) {
                    Optional<DataModel> dataModelOpt = addon.getData(json);
                    if (dataModelOpt.isEmpty()) {
                        LOGGER.warning("[IncrementDataHandler/" + addon.addonName() + "] model bulunamadı, key=" + keyValue);
                        return;
                    }

                    DataModel dataModel = dataModelOpt.get();
                    JsonNode  rootNode  = MAPPER.readTree(dataModel.getValueJson());

                    if (!rootNode.has(field) || !rootNode.get(field).isNumber()) {
                        LOGGER.warning("[IncrementDataHandler/" + addon.addonName() + "] alan eksik ya da sayı değil -> " + field);
                        return;
                    }

                    ObjectNode updatedNode = (ObjectNode) rootNode;
                    JsonNode   targetNode  = rootNode.get(field);

                    if (targetNode.isIntegralNumber()) {
                        updatedNode.put(field, targetNode.asLong() + amount.longValue());
                    } else {
                        updatedNode.put(field, targetNode.asDouble() + amount.doubleValue());
                    }

                    String updatedJson = MAPPER.writeValueAsString(updatedNode);

                    dataModel.setValueJson(updatedJson);

                    app.getRedisManager().setData(dataModel.getKey(), updatedJson, dataModel.getAddon());

                    addon.pushMetrics(new NexusJsonDataContainer(updatedJson));
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[IncrementDataHandler/" + addon.addonName() + "] hata", e);
            } finally {
                if (lockKey != null && lock != null) {
                    addon.releaseKeyLock(lockKey, lock);
                }
            }
        });
    }
}
