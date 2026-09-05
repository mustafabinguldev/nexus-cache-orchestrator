package network.darkland.protocol.handlers;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestHandler;
import network.darkland.redis.MessageAuth;
import network.darkland.redis.RedisManager;

public final class RankFinderHandler implements RequestHandler {

    @Override
    public void handle(DataAddon addon, String source, NexusJsonDataContainer json) {
        if (!json.containsKey("field") || !json.containsKey("key") || !json.containsKey("order")) return;

        String field = json.get("field", String.class);
        String key   = json.get("key",   String.class);
        String order = json.get("order", String.class);

        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() ->
                app.getMongoManager().getPosition(addon, key, field, order)
                        .thenAccept(position -> {
                            NexusJsonDataContainer response = new NexusJsonDataContainer();
                            response.set("protocol", addon.addonId());
                            response.set("type",     "RANK_FINDER_RESPONSE");
                            response.set("target",   source);
                            response.set("key",      key);
                            response.set("position", position);
                            app.getRedisManager().publish(
                                    RedisManager.CHANNEL + "_" + source,
                                    MessageAuth.stamp(response.toFullJson())
                            );
                        })
        );
    }
}
