package network.darkland.protocol.handlers;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestHandler;
import network.darkland.redis.MessageAuth;
import network.darkland.redis.RedisManager;

public final class RankingHandler implements RequestHandler {

    @Override
    public void handle(DataAddon addon, String source, NexusJsonDataContainer json) {
        if (!json.containsKey("field") || !json.containsKey("order") || !json.containsKey("limit")) return;

        String field = json.get("field", String.class);
        String order = json.get("order", String.class);
        int    limit = json.get("limit", Integer.class);

        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() ->
                app.getMongoManager().getRanking(addon, field, order, limit)
                        .thenAccept(rankingMap -> {
                            NexusJsonDataContainer response = new NexusJsonDataContainer();
                            response.set("protocol", addon.addonId());
                            response.set("type",     "RANKING_RESPONSE");
                            response.set("target",   source);
                            response.set("response", rankingMap);
                            app.getRedisManager().publish(
                                    RedisManager.CHANNEL + "_" + source,
                                    MessageAuth.stamp(response.toFullJson())
                            );
                        })
                        .exceptionally(ex -> { ex.printStackTrace(); return null; })
        );
    }
}
