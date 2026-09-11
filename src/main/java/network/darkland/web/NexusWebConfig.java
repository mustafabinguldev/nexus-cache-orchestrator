package network.darkland.web;

import network.darkland.db.DatabaseType;
import network.darkland.db.DbConnectionConfig;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;

public class NexusWebConfig {

    private static final Path CONFIG_PATH = Path.of("config.json");

    public final String redisHost;
    public final int redisPort;
    public final String redisUser;
    public final String redisPass;
    public final DbConnectionConfig dbConnectionConfig;
    public final boolean metricsEnabled;
    public final String influxUrl;
    public final String influxToken;
    public final String influxOrg;
    public final String influxBucket;
    public final int webPort;
    public final String adminUsername;
    public final String adminPasswordHash;

    private NexusWebConfig(JSONObject cfg) {
        this.redisHost = cfg.optString("redisHost", "127.0.0.1");
        this.redisPort = cfg.optInt("redisPort", 6379);
        this.redisUser = cfg.optString("redisUser", "");
        this.redisPass = cfg.optString("redisPass", "");
        this.dbConnectionConfig = parseDbConnectionConfig(cfg);
        this.metricsEnabled = cfg.optBoolean("metricsEnabled", false);

        String iUrl = null, iToken = null, iOrg = null, iBucket = null;
        if (metricsEnabled && cfg.has("influx")) {
            JSONObject influx = cfg.getJSONObject("influx");
            iUrl    = influx.optString("url", null);
            iToken  = influx.optString("token", null);
            iOrg    = influx.optString("org", null);
            iBucket = influx.optString("bucket", null);
        }
        this.influxUrl = iUrl;
        this.influxToken = iToken;
        this.influxOrg = iOrg;
        this.influxBucket = iBucket;

        this.webPort = cfg.optInt("webPort", 8088);
        this.adminUsername = cfg.optString("adminUsername", "");
        this.adminPasswordHash = cfg.optString("adminPasswordHash", "");
    }

    private static DbConnectionConfig parseDbConnectionConfig(JSONObject cfg) {
        if (!cfg.has("dbType")) {
            // Legacy config.json: always Mongo, connection string at the top level.
            String legacyUri = cfg.optString("mongoUri", "mongodb://localhost:27017");
            return DbConnectionConfig.mongo(legacyUri);
        }

        DatabaseType type = DatabaseType.from(cfg.optString("dbType", "MONGODB"));
        JSONObject db = cfg.optJSONObject("db");
        if (db == null) db = new JSONObject();

        return switch (type) {
            case MONGODB -> DbConnectionConfig.mongo(db.optString("mongoUri", "mongodb://localhost:27017"));
            case MYSQL -> DbConnectionConfig.mysql(
                    db.optString("host", "127.0.0.1"),
                    db.optInt("port", 3306),
                    db.optString("database", "nexus"),
                    db.optString("username", "root"),
                    db.optString("password", ""));
            case MARIADB -> DbConnectionConfig.mariadb(
                    db.optString("host", "127.0.0.1"),
                    db.optInt("port", 3306),
                    db.optString("database", "nexus"),
                    db.optString("username", "root"),
                    db.optString("password", ""));
            case POSTGRESQL -> DbConnectionConfig.postgresql(
                    db.optString("host", "127.0.0.1"),
                    db.optInt("port", 5432),
                    db.optString("database", "nexus"),
                    db.optString("username", "postgres"),
                    db.optString("password", ""));
            case MSSQL -> DbConnectionConfig.mssql(
                    db.optString("host", "127.0.0.1"),
                    db.optInt("port", 1433),
                    db.optString("database", "nexus"),
                    db.optString("username", "sa"),
                    db.optString("password", ""));
            case SQLITE -> DbConnectionConfig.sqlite(db.optString("filePath", "nexus-data.db"));
        };
    }

    public static NexusWebConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) return null;
            String content = Files.readString(CONFIG_PATH);
            return new NexusWebConfig(new JSONObject(content));
        } catch (Exception e) {
            System.err.println("config.json could not be read: " + e.getMessage());
            return null;
        }
    }

    public boolean isValid() {
        return !adminUsername.isBlank() && !adminPasswordHash.isBlank();
    }
}

