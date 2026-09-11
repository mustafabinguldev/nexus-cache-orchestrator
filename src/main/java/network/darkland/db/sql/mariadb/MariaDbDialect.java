package network.darkland.db.sql.mariadb;

import network.darkland.db.sql.SqlDialect;

import java.util.List;
import java.util.Locale;

/**
 * MariaDB specific SQL fragments. Kept as its own dialect (rather than reusing
 * {@link network.darkland.db.sql.mysql.MySqlDialect}) even though the SQL is very similar,
 * because it uses a different JDBC driver/URL scheme and the two engines are free to diverge
 * (MariaDB's {@code JSON} type is a {@code LONGTEXT} alias with a validity check, not a real
 * native JSON type like MySQL's).
 */
public final class MariaDbDialect implements SqlDialect {

    @Override
    public String driverClassName() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database) {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&connectTimeout=2000&socketTimeout=5000";
    }

    @Override
    public String jsonColumnType() {
        return "LONGTEXT CHECK (JSON_VALID(data))";
    }

    @Override
    public String createTableSql(String table) {
        return "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "id_key VARCHAR(191) NOT NULL PRIMARY KEY, "
                + "data " + jsonColumnType()
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public String upsertSql(String table) {
        return "INSERT INTO `" + table + "` (id_key, data) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE data = VALUES(data)";
    }

    @Override
    public String numericFieldExpression(String jsonColumn, String fieldName) {
        return "CAST(JSON_UNQUOTE(JSON_EXTRACT(`" + jsonColumn + "`, '$." + fieldName + "')) AS DECIMAL(30,6))";
    }

    @Override
    public List<String> createIndexStatements(String table, String indexName, String fieldName) {
        String normalizedIndex = indexName.toLowerCase(Locale.ROOT);
        String columnName = normalizedIndex.substring(0, Math.min(normalizedIndex.length(), 59)) + "_col";
        return List.of(
                "ALTER TABLE `" + table + "` ADD COLUMN IF NOT EXISTS `" + columnName
                        + "` DECIMAL(30,6) AS (" + numericFieldExpression("data", fieldName) + ") PERSISTENT",
                "CREATE INDEX IF NOT EXISTS `" + indexName + "` ON `" + table + "` (`" + columnName + "`)"
        );
    }
}
