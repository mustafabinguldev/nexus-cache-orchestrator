package network.darkland.protocol.handlers;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestHandler;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class RemoveDataHandler implements RequestHandler {

    private static final Logger LOGGER = Logger.getLogger(RemoveDataHandler.class.getName());

    @Override
    public void handle(DataAddon addon, String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            try {
                String idFieldName = addon.getIdFieldName();
                if (idFieldName.isEmpty() || !json.containsKey(idFieldName)) return;
                if (!json.containsKey("all")) return;

                String  specificId = json.get(idFieldName, addon.getIdClassName()).toString();
                boolean allRemove  = Boolean.TRUE.equals(json.get("all", Boolean.class));

                addon.getData(json).ifPresent(dataModel -> {
                    app.getDataContainer().removeModel(dataModel.getKey());
                    if (allRemove) {
                        app.getRedisManager().processMongoTask(() ->
                                app.getMongoManager().removeValue(addon, specificId).join()
                        );
                    }
                });

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[RemoveDataHandler/" + addon.addonName() + "] hata", e);
            }
        });
    }
}
