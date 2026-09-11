package network.darkland.db.sql.mssql;

import network.darkland.db.sql.SqlDialect;

import java.util.List;
import java.util.Locale;

/**
 * Microsoft SQL Server specific SQL fragments. T-SQL is the most different dialect Nexus
 * supports, which is exactly why the {@link SqlDialect} abstraction exists:
 * <ul>
 *   <li>no {@code CREATE TABLE IF NOT EXISTS} — wrapped in an {@code IF NOT EXISTS (...)} check</li>
 *   <li>no {@code ON DUPLICATE KEY} / {@code ON CONFLICT} — uses a {@code MERGE} statement</li>
 *   <li>no {@code LIMIT} — uses {@code SELECT TOP (?)}, see {@link #rankingQuery}</li>
 *   <li>no expression/functional indexes — needs a persisted computed column first,
 *       see {@link #createIndexStatements}</li>
 * </ul>
 */
public final class MsSqlDialect implements SqlDialect {

    @Override
    public String driverClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database) {
        return "jdbc:sqlserver://" + host + ":" + port
                + ";databaseName=" + database
                + ";encrypt=false;trustServerCertificate=true;loginTimeout=2";
    }

    @Override
    public String jsonColumnType() {
        return "NVARCHAR(MAX)";
    }

    @Override
    public String createTableSql(String table) {
        return "IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = '" + table + "') "
                + "CREATE TABLE [" + table + "] ("
                + "id_key NVARCHAR(255) NOT NULL PRIMARY KEY, "
                + "data " + jsonColumnType() + " NOT NULL"
                + ")";
    }

    @Override
    public String upsertSql(String table) {
        return "MERGE INTO [" + table + "] AS target "
                + "USING (SELECT ? AS id_key, ? AS data) AS source "
                + "ON target.id_key = source.id_key "
                + "WHEN MATCHED THEN UPDATE SET data = source.data "
                + "WHEN NOT MATCHED THEN INSERT (id_key, data) VALUES (source.id_key, source.data);";
    }

    @Override
    public String numericFieldExpression(String jsonColumn, String fieldName) {
        return "CAST(JSON_VALUE(" + jsonColumn + ", '$." + fieldName + "') AS DECIMAL(30,6))";
    }

    @Override
    public String rankingQuery(String table, String orderExpression, String direction) {
        return "SELECT TOP (?) data FROM [" + table + "] ORDER BY " + orderExpression + " " + direction;
    }

    @Override
    public List<String> createIndexStatements(String table, String indexName, String fieldName) {
        // SQL Server has no functional/expression index, so materialize the numeric value
        // into a persisted computed column first, then index that column.
        String columnName = (indexName + "_col").toLowerCase(Locale.ROOT);

        String addComputedColumn =
                "IF COL_LENGTH('" + table + "', '" + columnName + "') IS NULL "
                        + "ALTER TABLE [" + table + "] ADD [" + columnName + "] "
                        + "AS (" + numericFieldExpression("data", fieldName) + ") PERSISTED";

        String createIndex =
                "IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = '" + indexName
                        + "' AND object_id = OBJECT_ID('" + table + "')) "
                        + "CREATE INDEX [" + indexName + "] ON [" + table + "] ([" + columnName + "])";

        return List.of(addComputedColumn, createIndex);
    }
}
