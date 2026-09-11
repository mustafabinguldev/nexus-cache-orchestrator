package network.darkland.db.sql.sqlite;

import network.darkland.db.sql.SqlDialect;

import java.util.List;

public final class SqliteDialect implements SqlDialect {

    @Override
    public String driverClassName() {
        return "org.sqlite.JDBC";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database) {
        throw new UnsupportedOperationException("SQLite is file-based; use buildJdbcUrlForFile(filePath) instead");
    }

    @Override
    public String buildJdbcUrlForFile(String filePath) {
        return "jdbc:sqlite:" + filePath;
    }

    @Override
    public String jsonColumnType() {
        return "TEXT";
    }

    @Override
    public String createTableSql(String table) {
        return "CREATE TABLE IF NOT EXISTS \"" + table + "\" ("
                + "id_key TEXT PRIMARY KEY, "
                + "data " + jsonColumnType() + " NOT NULL"
                + ")";
    }

    @Override
    public String upsertSql(String table) {
        return "INSERT INTO \"" + table + "\" (id_key, data) VALUES (?, ?) "
                + "ON CONFLICT(id_key) DO UPDATE SET data = excluded.data";
    }

    @Override
    public String numericFieldExpression(String jsonColumn, String fieldName) {
        return "CAST(json_extract(" + jsonColumn + ", '$." + fieldName + "') AS REAL)";
    }

    @Override
    public List<String> createIndexStatements(String table, String indexName, String fieldName) {
        String sql = "CREATE INDEX IF NOT EXISTS \"" + indexName + "\" ON \"" + table + "\" ("
                + numericFieldExpression("data", fieldName) + ")";
        return List.of(sql);
    }
}
