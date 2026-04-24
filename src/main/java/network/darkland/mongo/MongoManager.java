package network.darkland.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import network.darkland.protocol.DataAddon;
import org.bson.Document;

import java.util.concurrent.CompletableFuture;

public class MongoManager {

    private final MongoClient client;

    public MongoManager(String uri) {
        this.client = MongoClients.create(uri);
    }


    public CompletableFuture<Boolean> exists(DataAddon addon, String key) {
        return CompletableFuture.supplyAsync(() -> {
            var collection = client.getDatabase(addon.getDatabase()).getCollection(addon.getCollection());
            return collection.countDocuments(Filters.eq(addon.getIdFieldName(), key)) > 0;
        });
    }

    public CompletableFuture<String> getValue(DataAddon addon, String key) {
        return CompletableFuture.supplyAsync(() -> {
            Document doc = client.getDatabase(addon.getDatabase())
                    .getCollection(addon.getCollection())
                    .find(Filters.eq(addon.getIdFieldName(), key))
                    .first();
            return doc != null ? doc.toJson() : null;
        });
    }



    public CompletableFuture<Void> removeValue(DataAddon addon, String key) {
        return CompletableFuture.runAsync(() -> {
            var collection = client.getDatabase(addon.getDatabase()).getCollection(addon.getCollection());
            collection.deleteOne(Filters.eq(addon.getIdFieldName(), key));
        });
    }


    public CompletableFuture<Void> setValue(DataAddon addon, String key, String jsonValue) {
        return CompletableFuture.runAsync(() -> {
            var collection = client.getDatabase(addon.getDatabase()).getCollection(addon.getCollection());

            Document doc = Document.parse(jsonValue);
            collection.replaceOne(
                    Filters.eq(addon.getIdFieldName(), key),
                    doc,
                    new ReplaceOptions().upsert(true)
            );
        });
    }
}