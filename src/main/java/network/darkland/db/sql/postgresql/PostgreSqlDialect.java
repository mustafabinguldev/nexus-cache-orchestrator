package network.darkland.db.sql.postgresql;

import network.darkland.db.sql.SqlDialect;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public final class PostgreSqlDialect implements SqlDialect {

    @Override
    public String driverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?connectTimeout=2&socketTimeout=5";
    }

    @Override
    public String jsonColumnType() {
        return "JSONB";
    }

    @Override
    public String createTableSql(String table) {
        return "CREATE TABLE IF NOT EXISTS \"" + table + "\" ("
                + "id_key VARCHAR(255) PRIMARY KEY, "
                + "data " + jsonColumnType() + " NOT NULL"
                + ")";
    }

    @Override
    public String upsertSql(String table) {
        return "INSERT INTO \"" + table + "\" (id_key, data) VALUES (?, ?::jsonb) "
                + "ON CONFLICT (id_key) DO UPDATE SET data = EXCLUDED.data";
    }

    @Override
    public String numericFieldExpression(String jsonColumn, String fieldName) {
        return "((" + jsonColumn + "->>'" + fieldName + "')::numeric)";
    }

    private String createIndexSql(String table, String indexName, String fieldName) {
        return "CREATE INDEX IF NOT EXISTS \"" + indexName + "\" ON \"" + table + "\" ("
                + numericFieldExpression("data", fieldName) + ")";
    }

    @Override
    public java.util.List<String> createIndexStatements(String table, String indexName, String fieldName) {
        return java.util.List.of(createIndexSql(table, indexName, fieldName));
    }

    @Override
    public void bindJson(PreparedStatement ps, int index, String json) throws SQLException {
        ps.setObject(index, json, Types.OTHER);
    }
}
