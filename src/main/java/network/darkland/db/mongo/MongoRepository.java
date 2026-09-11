package network.darkland.db.mongo;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import network.darkland.protocol.DataAddon;
import network.darkland.resilience.ResilienceConfig;
import network.darkland.resilience.ResilienceExecutor;
import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public final class MongoRepository {

    private final MongoConnectionManager connectionManager;
    private final ExecutorService executor;
    private final ResilienceConfig resilience;

    public MongoRepository(MongoConnectionManager connectionManager, ExecutorService executor, ResilienceConfig resilience) {
        this.connectionManager = connectionManager;
        this.executor = executor;
        this.resilience = resilience;
    }

    private com.mongodb.client.MongoCollection<Document> collection(DataAddon addon) {
        return connectionManager.client()
                .getDatabase(addon.getNamespace())
                .getCollection(addon.getDataset());
    }

    public CompletableFuture<Boolean> exists(DataAddon addon, String key) {
        return ResilienceExecutor.decorateAsync(
                resilience.mongoCircuitBreaker(),
                resilience.mongoRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() ->
                        collection(addon).countDocuments(Filters.eq(addon.getIdFieldName(), key)) > 0, executor)
        );
    }

    public CompletableFuture<String> getValue(DataAddon addon, String key) {
        return ResilienceExecutor.decorateAsync(
                resilience.mongoCircuitBreaker(),
                resilience.mongoRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    Document doc = collection(addon)
                            .find(Filters.eq(addon.getIdFieldName(), key))
                            .first();
                    return doc != null ? doc.toJson() : null;
                }, executor)
        );
    }

    public CompletableFuture<Void> removeValue(DataAddon addon, String key) {
        return ResilienceExecutor.decorateAsync(
                resilience.mongoCircuitBreaker(),
                resilience.mongoRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.runAsync(() ->
                        collection(addon).deleteOne(Filters.eq(addon.getIdFieldName(), key)), executor)
        );
    }

    public CompletableFuture<Void> setValue(DataAddon addon, String key, String jsonValue) {
        return ResilienceExecutor.decorateAsync(
                resilience.mongoCircuitBreaker(),
                resilience.mongoRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.runAsync(() -> {
                    Document doc = Document.parse(jsonValue);
                    collection(addon).replaceOne(
                            Filters.eq(addon.getIdFieldName(), key),
                            doc,
                            new ReplaceOptions().upsert(true)
                    );
                }, executor)
        );
    }

    public CompletableFuture<Map<Integer, Object>> getRanking(DataAddon addon, String fieldName, String orderType, int limitCount) {
        return ResilienceExecutor.decorateAsync(
                resilience.mongoCircuitBreaker(),
                resilience.mongoRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    Map<Integer, Object> rankingMap = new LinkedHashMap<>();

                    var sortOrder = orderType.equalsIgnoreCase("DESC")
                            ? Sorts.descending(fieldName)
                            : Sorts.ascending(fieldName);

                    AtomicInteger rank = new AtomicInteger(1);

                    collection(addon)
                            .find()
                            .sort(sortOrder)
                            .limit(limitCount)
                            .forEach(doc -> {
                                doc.remove("_id");
                                rankingMap.put(rank.getAndIncrement(), doc);
                            });

                    return rankingMap;
                }, executor)
        );
    }

    public CompletableFuture<Integer> getPosition(DataAddon addon, String key, String fieldName, String orderType) {
        return ResilienceExecutor.decorateAsync(
                resilience.mongoCircuitBreaker(),
                resilience.mongoRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    var collection = collection(addon);
                    Document doc = collection.find(Filters.eq(addon.getIdFieldName(), key)).first();

                    if (doc == null || !doc.containsKey(fieldName)) return -1;

                    Object value = doc.get(fieldName);

                    var filter = orderType.equalsIgnoreCase("DESC")
                            ? Filters.gt(fieldName, value)
                            : Filters.lt(fieldName, value);

                    long countAhead = collection.countDocuments(filter);
                    return (int) (countAhead + 1);
                }, executor)
        );
    }

    public void ensureIndex(DataAddon addon, String fieldName) {
        collection(addon).createIndex(Indexes.ascending(fieldName));
    }
}
