package network.darkland.db;

public final class DbConnectionConfig {

    private final DatabaseType type;

    // MongoDB
    private final String mongoUri;

    // MySQL / MariaDB / PostgreSQL / MSSQL
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    // SQLite
    private final String filePath;

    private DbConnectionConfig(DatabaseType type, String mongoUri,
                                String host, int port, String database,
                                String username, String password, String filePath) {
        this.type = type;
        this.mongoUri = mongoUri;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.filePath = filePath;
    }

    public static DbConnectionConfig mongo(String mongoUri) {
        return new DbConnectionConfig(DatabaseType.MONGODB, mongoUri, null, 0, null, null, null, null);
    }

    public static DbConnectionConfig mysql(String host, int port, String database, String username, String password) {
        return new DbConnectionConfig(DatabaseType.MYSQL, null, host, port, database, username, password, null);
    }

    public static DbConnectionConfig mariadb(String host, int port, String database, String username, String password) {
        return new DbConnectionConfig(DatabaseType.MARIADB, null, host, port, database, username, password, null);
    }

    public static DbConnectionConfig postgresql(String host, int port, String database, String username, String password) {
        return new DbConnectionConfig(DatabaseType.POSTGRESQL, null, host, port, database, username, password, null);
    }

    public static DbConnectionConfig mssql(String host, int port, String database, String username, String password) {
        return new DbConnectionConfig(DatabaseType.MSSQL, null, host, port, database, username, password, null);
    }

    public static DbConnectionConfig sqlite(String filePath) {
        return new DbConnectionConfig(DatabaseType.SQLITE, null, null, 0, null, null, null, filePath);
    }

    public DatabaseType type() { return type; }
    public String mongoUri() { return mongoUri; }
    public String host() { return host; }
    public int port() { return port; }
    public String database() { return database; }
    public String username() { return username; }
    public String password() { return password; }
    public String filePath() { return filePath; }
}

