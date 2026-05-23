package network.darkland.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import network.darkland.protocol.DataAddon;
import org.bson.Document;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class MongoManager {

    private final MongoClient client;

    public MongoManager(String uri) {
        ConnectionString connectionString =
                new ConnectionString(uri);

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)

                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(2, TimeUnit.SECONDS)
                )

                .applyToSocketSettings(builder ->
                        builder.connectTimeout(2, TimeUnit.SECONDS)
                                .readTimeout(2, TimeUnit.SECONDS)
                )

                .build();

        client = MongoClients.create(settings);
    }


    public boolean verifyConnection() {
        try {
            client.getDatabase("admin")
                    .runCommand(new Document("ping", 1));

            System.out.println("[MongoDB] Connection has been checked");
            return true;

        } catch (Exception e) {
            System.err.println("[MongoDB] Connection failed: " + e.getMessage());
            return false;
        }
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


    public CompletableFuture<java.util.Map<Integer, Object>> getRanking(DataAddon addon, String fieldName, String orderType, int limitCount) {
        return CompletableFuture.supplyAsync(() -> {

            java.util.Map<Integer, Object> rankingMap = new java.util.LinkedHashMap<>();

            var sortOrder = orderType.equalsIgnoreCase("DESC")
                    ? Sorts.descending(fieldName)
                    : Sorts.ascending(fieldName);

            java.util.concurrent.atomic.AtomicInteger rank = new java.util.concurrent.atomic.AtomicInteger(1);

            client.getDatabase(addon.getDatabase())
                    .getCollection(addon.getCollection())
                    .find()
                    .sort(sortOrder)
                    .limit(limitCount)
                    .forEach(doc -> {
                        doc.remove("_id");
                        rankingMap.put(rank.getAndIncrement(), doc);
                    });

            return rankingMap;
        });
    }

    public CompletableFuture<Integer> getPosition(DataAddon addon, String key, String fieldName, String orderType) {
        return CompletableFuture.supplyAsync(() -> {
            var collection = client.getDatabase(addon.getDatabase())
                    .getCollection(addon.getCollection());

            Document playerDoc = collection.find(Filters.eq(addon.getIdFieldName(), key)).first();

            if (playerDoc == null || !playerDoc.containsKey(fieldName)) return -1;

            Object value = playerDoc.get(fieldName);

            var filter = orderType.equalsIgnoreCase("DESC")
                    ? Filters.gt(fieldName, value)
                    : Filters.lt(fieldName, value);

            long countAhead = collection.countDocuments(filter);

            return (int) (countAhead + 1);
        });
    }

}