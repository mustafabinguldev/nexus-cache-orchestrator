package network.darkland.db.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import network.darkland.protocol.DataAddon;
import network.darkland.resilience.ResilienceConfig;
import network.darkland.resilience.ResilienceExecutor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqlRepository {

    private static final Logger LOGGER = Logger.getLogger(SqlRepository.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SqlConnectionManager connectionManager;
    private final SqlDialect dialect;
    private final ExecutorService executor;
    private final ResilienceConfig resilience;

    private final ConcurrentHashMap<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

    public SqlRepository(SqlConnectionManager connectionManager, SqlDialect dialect,
                          ExecutorService executor, ResilienceConfig resilience) {
        this.connectionManager = connectionManager;
        this.dialect = dialect;
        this.executor = executor;
        this.resilience = resilience;
    }

    private String tableName(DataAddon addon) {
        String raw = "nexus_" + addon.getNamespace() + "_" + addon.getDataset();
        return sanitizeIdentifier(raw);
    }

    static String sanitizeIdentifier(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (cleaned.equals(raw) && cleaned.length() <= 63) return cleaned;

        String hash = sha256(raw).substring(0, 12);
        int prefixLength = Math.min(cleaned.length(), 63 - hash.length() - 1);
        return cleaned.substring(0, prefixLength) + "_" + hash;
    }

    static String validateFieldName(String fieldName) {
        if (fieldName == null || !fieldName.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid ranking field name");
        }
        return fieldName;
    }

    static int validateRankingLimit(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("Ranking limit must be between 1 and 1000");
        }
        return limit;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static Object parseJsonData(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("[SQL] Stored data is not valid JSON", e);
        }
    }

    private void ensureTable(DataAddon addon) {
        String table = tableName(addon);
        if (ensuredTables.containsKey(table)) return;

        synchronized (ensuredTables) {
            if (ensuredTables.containsKey(table)) return;
            try (Connection conn = connectionManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(dialect.createTableSql(table))) {
                ps.executeUpdate();
                ensuredTables.put(table, Boolean.TRUE);
            } catch (SQLException e) {
                throw new IllegalStateException("[SQL] Could not create/verify table " + table, e);
            }
        }
    }

    public CompletableFuture<Boolean> exists(DataAddon addon, String key) {
        return ResilienceExecutor.decorateAsync(
                resilience.sqlCircuitBreaker(),
                resilience.sqlRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    ensureTable(addon);
                    String sql = "SELECT 1 FROM " + tableName(addon) + " WHERE id_key = ?";
                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next();
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] exists() failed for key=" + key, e);
                    }
                }, executor)
        );
    }

    public CompletableFuture<String> getValue(DataAddon addon, String key) {
        return ResilienceExecutor.decorateAsync(
                resilience.sqlCircuitBreaker(),
                resilience.sqlRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    ensureTable(addon);
                    String sql = "SELECT data FROM " + tableName(addon) + " WHERE id_key = ?";
                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? rs.getString("data") : null;
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] getValue() failed for key=" + key, e);
                    }
                }, executor)
        );
    }

    public CompletableFuture<Void> removeValue(DataAddon addon, String key) {
        return ResilienceExecutor.decorateAsync(
                resilience.sqlCircuitBreaker(),
                resilience.sqlRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.runAsync(() -> {
                    ensureTable(addon);
                    String sql = "DELETE FROM " + tableName(addon) + " WHERE id_key = ?";
                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, key);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] removeValue() failed for key=" + key, e);
                    }
                }, executor)
        );
    }

    public CompletableFuture<Void> setValue(DataAddon addon, String key, String jsonValue) {
        return ResilienceExecutor.decorateAsync(
                resilience.sqlCircuitBreaker(),
                resilience.sqlRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.runAsync(() -> {
                    ensureTable(addon);
                    String table = tableName(addon);
                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(dialect.upsertSql(table))) {
                        ps.setString(1, key);
                        dialect.bindJson(ps, 2, jsonValue);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] setValue() failed for key=" + key, e);
                    }
                }, executor)
        );
    }

    public CompletableFuture<Map<Integer, Object>> getRanking(DataAddon addon, String fieldName, String orderType, int limitCount) {
        fieldName = validateFieldName(fieldName);
        limitCount = validateRankingLimit(limitCount);
        String validatedFieldName = fieldName;
        int validatedLimitCount = limitCount;
        return ResilienceExecutor.decorateAsync(
                resilience.sqlCircuitBreaker(),
                resilience.sqlRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    ensureTable(addon);
                    String table = tableName(addon);
                    String direction = "DESC".equalsIgnoreCase(orderType) ? "DESC" : "ASC";
                    String orderExpr = dialect.numericFieldExpression("data", validatedFieldName);

                    String sql = dialect.rankingQuery(table, orderExpr, direction);

                    Map<Integer, Object> rankingMap = new LinkedHashMap<>();
                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setInt(1, validatedLimitCount);
                        int rank = 1;
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rankingMap.put(rank++, parseJsonData(rs.getString("data")));
                            }
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] getRanking() failed for field=" + validatedFieldName, e);
                    }
                    return rankingMap;
                }, executor)
        );
    }

    public CompletableFuture<Integer> getPosition(DataAddon addon, String key, String fieldName, String orderType) {
        fieldName = validateFieldName(fieldName);
        String validatedFieldName = fieldName;
        return ResilienceExecutor.decorateAsync(
                resilience.sqlCircuitBreaker(),
                resilience.sqlRetry(),
                resilience.retryScheduler(),
                () -> CompletableFuture.supplyAsync(() -> {
                    ensureTable(addon);
                    String table = tableName(addon);
                    String fieldExpr = dialect.numericFieldExpression("data", validatedFieldName);

                    Double value = null;
                    String selectSql = "SELECT " + fieldExpr + " AS field_value FROM " + table + " WHERE id_key = ?";
                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(selectSql)) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                Object v = rs.getObject("field_value");
                                if (v != null) value = ((Number) v).doubleValue();
                            }
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] getPosition() lookup failed for key=" + key, e);
                    }

                    if (value == null) return -1;

                    boolean desc = "DESC".equalsIgnoreCase(orderType);
                    String comparator = desc ? ">" : "<";
                    String countSql = "SELECT COUNT(*) AS ahead FROM " + table + " WHERE " + fieldExpr + " " + comparator + " ?";

                    try (Connection conn = connectionManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(countSql)) {
                        ps.setDouble(1, value);
                        try (ResultSet rs = ps.executeQuery()) {
                            long ahead = rs.next() ? rs.getLong("ahead") : 0L;
                            return (int) (ahead + 1);
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("[SQL] getPosition() count failed for key=" + key, e);
                    }
                }, executor)
        );
    }

    public void ensureIndex(DataAddon addon, String fieldName) {
        fieldName = validateFieldName(fieldName);
        ensureTable(addon);
        String table = tableName(addon);
        String indexName = sanitizeIdentifier("idx_" + table + "_" + fieldName);
        for (String statement : dialect.createIndexStatements(table, indexName, fieldName)) {
            try (Connection conn = connectionManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(statement)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "[SQL] ensureIndex() statement failed for "
                        + table + "." + fieldName + " (likely already applied): " + e.getMessage());
            }
        }
    }
}
