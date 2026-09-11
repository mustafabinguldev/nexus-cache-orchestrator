package network.darkland.db.sql.postgresql;

import network.darkland.db.DataStore;
import network.darkland.db.DatabaseType;
import network.darkland.db.sql.SqlConnectionManager;
import network.darkland.db.sql.SqlRepository;
import network.darkland.protocol.DataAddon;
import network.darkland.resilience.ResilienceConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public final class PostgreSqlDataStore implements DataStore {

    private final SqlConnectionManager connectionManager;
    private final SqlRepository repository;

    public PostgreSqlDataStore(String host, int port, String database, String username, String password,
                                ExecutorService executor, ResilienceConfig resilience) {
        PostgreSqlDialect dialect = new PostgreSqlDialect();
        this.connectionManager = new SqlConnectionManager(
                dialect.buildJdbcUrl(host, port, database), username, password,
                dialect.driverClassName(), "Nexus-PostgreSQL-Pool");
        this.repository = new SqlRepository(connectionManager, dialect, executor, resilience);
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.POSTGRESQL;
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
