package network.darkland.db.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqlConnectionManager {

    private static final Logger LOGGER = Logger.getLogger(SqlConnectionManager.class.getName());

    private final HikariDataSource dataSource;

    public SqlConnectionManager(String jdbcUrl, String username, String password,
                                 String driverClassName, String poolName) {
        this(jdbcUrl, username, password, driverClassName, poolName, 10);
    }

    public SqlConnectionManager(String jdbcUrl, String username, String password,
                                 String driverClassName, String poolName, int maxPoolSize) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        if (username != null) hikariConfig.setUsername(username);
        if (password != null) hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName(driverClassName);
        hikariConfig.setPoolName(poolName);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(2_000);
        hikariConfig.setValidationTimeout(2_000);
        hikariConfig.setInitializationFailTimeout(-1); // don't crash the JVM if the DB is briefly unreachable at boot

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws java.sql.SQLException {
        return dataSource.getConnection();
    }

    public boolean verifyConnection() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SQL] Connection check failed", e);
            return false;
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
