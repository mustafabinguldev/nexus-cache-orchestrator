package network.darkland.db;

import network.darkland.db.mongo.MongoDataStore;
import network.darkland.db.sql.mariadb.MariaDbDataStore;
import network.darkland.db.sql.mssql.MsSqlDataStore;
import network.darkland.db.sql.mysql.MySqlDataStore;
import network.darkland.db.sql.postgresql.PostgreSqlDataStore;
import network.darkland.db.sql.sqlite.SqliteDataStore;
import network.darkland.resilience.ResilienceConfig;

import java.util.concurrent.ExecutorService;

public final class DataStoreFactory {

    private DataStoreFactory() {
    }

    public static DataStore create(DbConnectionConfig config, ExecutorService executor, ResilienceConfig resilience) {
        return switch (config.type()) {
            case MONGODB -> new MongoDataStore(config.mongoUri(), executor, resilience);
            case MYSQL -> new MySqlDataStore(
                    config.host(), config.port(), config.database(),
                    config.username(), config.password(), executor, resilience);
            case MARIADB -> new MariaDbDataStore(
                    config.host(), config.port(), config.database(),
                    config.username(), config.password(), executor, resilience);
            case POSTGRESQL -> new PostgreSqlDataStore(
                    config.host(), config.port(), config.database(),
                    config.username(), config.password(), executor, resilience);
            case MSSQL -> new MsSqlDataStore(
                    config.host(), config.port(), config.database(),
                    config.username(), config.password(), executor, resilience);
            case SQLITE -> new SqliteDataStore(config.filePath(), executor, resilience);
        };
    }
}


