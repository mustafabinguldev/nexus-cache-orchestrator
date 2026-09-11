package network.darkland.db.sql.sqlite;

import network.darkland.db.DataStore;
import network.darkland.db.DatabaseType;
import network.darkland.db.sql.SqlConnectionManager;
import network.darkland.db.sql.SqlRepository;
import network.darkland.protocol.DataAddon;
import network.darkland.resilience.ResilienceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;


public final class SqliteDataStore implements DataStore {

    private final SqlConnectionManager connectionManager;
    private final SqlRepository repository;

    public SqliteDataStore(String filePath, ExecutorService executor, ResilienceConfig resilience) {
        ensureParentDirectoryExists(filePath);

        SqliteDialect dialect = new SqliteDialect();
        this.connectionManager = new SqlConnectionManager(
                dialect.buildJdbcUrlForFile(filePath), null, null,
                dialect.driverClassName(), "Nexus-SQLite-Pool", 1);
        this.repository = new SqlRepository(connectionManager, dialect, executor, resilience);
    }

    private static void ensureParentDirectoryExists(String filePath) {
        try {
            Path parent = Path.of(filePath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("[SQLite] Could not create directory for " + filePath, e);
        }
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.SQLITE;
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
