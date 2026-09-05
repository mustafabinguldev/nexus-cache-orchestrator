package network.darkland.protocol.handlers;

import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestHandler;
import network.darkland.util.JsonUtils;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SetDataHandler implements RequestHandler {

    private static final Logger LOGGER = Logger.getLogger(SetDataHandler.class.getName());

    @Override
    public void handle(DataAddon addon, String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            try {
                String rawInput = json.containsKey("data")
                        ? JsonUtils.toJson(json.get("data", Object.class))
                        : json.toFullJson();

                if (rawInput == null || !rawInput.trim().startsWith("{")) {
                    LOGGER.warning("[SetDataHandler/" + addon.addonName() + "] invalid JSON, source=" + source);
                    return;
                }

                Optional<DataModel> dataModelOpt = addon.getData(json);

                if (dataModelOpt.isEmpty()) {
                    DataModel newModel = addon.createModel(addon.modelInit(rawInput));
                    app.getDataContainer().addModelDirect(newModel.getKey(), newModel);
                    addon.pushMetrics(new NexusJsonDataContainer(newModel.getValueJson()));
                } else {
                    DataModel existing = dataModelOpt.get();
                    String updated  = addon.modelInitComp(rawInput);
                    existing.setValueJson(updated);

                    app.getRedisManager().setData(existing.getKey(), updated, existing.getAddon());
                    addon.pushMetrics(new NexusJsonDataContainer(updated));
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[SetDataHandler/" + addon.addonName() + "] error:", e);
            }
        });
    }
}
