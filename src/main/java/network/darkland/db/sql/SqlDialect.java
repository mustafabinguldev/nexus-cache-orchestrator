package network.darkland.db.sql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public interface SqlDialect {

    String driverClassName();

    String buildJdbcUrl(String host, int port, String database);

    default String buildJdbcUrlForFile(String filePath) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is not a file-based engine");
    }

    String jsonColumnType();

    String createTableSql(String table);

    String upsertSql(String table);

    String numericFieldExpression(String jsonColumn, String fieldName);

    default String rankingQuery(String table, String orderExpression, String direction) {
        return "SELECT data FROM " + table + " ORDER BY " + orderExpression + " " + direction + " LIMIT ?";
    }

    List<String> createIndexStatements(String table, String indexName, String fieldName);

    default void bindJson(PreparedStatement ps, int index, String json) throws SQLException {
        ps.setString(index, json);
    }
}

