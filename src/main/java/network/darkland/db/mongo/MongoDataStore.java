package network.darkland.db.mongo;

import network.darkland.db.DataStore;
import network.darkland.db.DatabaseType;
import network.darkland.protocol.DataAddon;
import network.darkland.resilience.ResilienceConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * MongoDB {@link DataStore}. Pure composition, no logic of its own: connection lifecycle
 * lives in {@link MongoConnectionManager}, query/write logic lives in {@link MongoRepository}.
 * This class only wires the two together and satisfies the {@link DataStore} contract.
 */
public final class MongoDataStore implements DataStore {

    private final MongoConnectionManager connectionManager;
    private final MongoRepository repository;

    public MongoDataStore(String uri, ExecutorService executor, ResilienceConfig resilience) {
        this.connectionManager = new MongoConnectionManager(uri);
        this.repository = new MongoRepository(connectionManager, executor, resilience);
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.MONGODB;
    }

    @Override
    public boolean verifyConnection() {
        return connectionManager.verifyConnection();
    }

    @Override
    public void close() {
        connectionManager.close();
    }

    @Override
    public CompletableFuture<Boolean> exists(DataAddon addon, String key) {
        return repository.exists(addon, key);
    }

    @Override
    public CompletableFuture<String> getValue(DataAddon addon, String key) {
        return repository.getValue(addon, key);
    }

    @Override
    public CompletableFuture<Void> removeValue(DataAddon addon, String key) {
        return repository.removeValue(addon, key);
    }

    @Override
    public CompletableFuture<Void> setValue(DataAddon addon, String key, String jsonValue) {
        return repository.setValue(addon, key, jsonValue);
    }

    @Override
    public CompletableFuture<Map<Integer, Object>> getRanking(DataAddon addon, String fieldName, String orderType, int limitCount) {
        return repository.getRanking(addon, fieldName, orderType, limitCount);
    }

    @Override
    public CompletableFuture<Integer> getPosition(DataAddon addon, String key, String fieldName, String orderType) {
        return repository.getPosition(addon, key, fieldName, orderType);
    }

    @Override
    public void ensureIndex(DataAddon addon, String fieldName) {
        repository.ensureIndex(addon, fieldName);
    }
}
