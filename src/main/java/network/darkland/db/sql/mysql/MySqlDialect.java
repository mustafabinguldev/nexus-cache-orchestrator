package network.darkland.db.sql.mysql;

import network.darkland.db.sql.SqlDialect;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * MySQL / MariaDB specific SQL fragments. This is the only place MySQL syntax appears —
 * {@link network.darkland.db.sql.SqlRepository} contains all the actual query orchestration.
 */
public final class MySqlDialect implements SqlDialect {

    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&connectTimeout=2000&socketTimeout=5000";
    }

    @Override
    public String jsonColumnType() {
        return "JSON";
    }

    @Override
    public String createTableSql(String table) {
        return "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "id_key VARCHAR(191) NOT NULL PRIMARY KEY, "
                + "data " + jsonColumnType() + " NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public String upsertSql(String table) {
        return "INSERT INTO `" + table + "` (id_key, data) VALUES (?, CAST(? AS JSON)) "
                + "ON DUPLICATE KEY UPDATE data = VALUES(data)";
    }

    @Override
    public String numericFieldExpression(String jsonColumn, String fieldName) {
        return "CAST(JSON_UNQUOTE(JSON_EXTRACT(`" + jsonColumn + "`, '$." + fieldName + "')) AS DECIMAL(30,6))";
    }

    private String createIndexSql(String table, String indexName, String fieldName) {
        return "CREATE INDEX `" + indexName + "` ON `" + table + "` ((" + numericFieldExpression("data", fieldName) + "))";
    }

    @Override
    public java.util.List<String> createIndexStatements(String table, String indexName, String fieldName) {
        return java.util.List.of(createIndexSql(table, indexName, fieldName));
    }

    @Override
    public void bindJson(PreparedStatement ps, int index, String json) throws SQLException {
        ps.setString(index, json);
    }
}
