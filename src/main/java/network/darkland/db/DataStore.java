package network.darkland.db;

import network.darkland.protocol.DataAddon;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface DataStore {

    DatabaseType type();

    boolean verifyConnection();

    void close();

    CompletableFuture<Boolean> exists(DataAddon addon, String key);

    CompletableFuture<String> getValue(DataAddon addon, String key);

    CompletableFuture<Void> removeValue(DataAddon addon, String key);

    CompletableFuture<Void> setValue(DataAddon addon, String key, String jsonValue);

    CompletableFuture<Map<Integer, Object>> getRanking(DataAddon addon, String fieldName, String orderType, int limitCount);

    CompletableFuture<Integer> getPosition(DataAddon addon, String key, String fieldName, String orderType);

    void ensureIndex(DataAddon addon, String fieldName);
}
