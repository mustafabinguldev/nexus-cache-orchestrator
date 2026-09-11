package network.darkland.db;

import java.util.Locale;

public enum DatabaseType {

    MONGODB,
    MYSQL,
    MARIADB,
    POSTGRESQL,
    MSSQL,
    SQLITE;

    public static DatabaseType from(String raw) {
        if (raw == null || raw.isBlank()) return MONGODB;

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mongo", "mongodb" -> MONGODB;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "postgres", "postgresql", "psql", "pg" -> POSTGRESQL;
            case "mssql", "sqlserver", "sql server", "sql_server" -> MSSQL;
            case "sqlite", "sqlite3" -> SQLITE;
            default -> throw new IllegalArgumentException("Unknown database type: " + raw);
        };
    }

    public String displayName() {
        return switch (this) {
            case MONGODB -> "MongoDB";
            case MYSQL -> "MySQL";
            case MARIADB -> "MariaDB";
            case POSTGRESQL -> "PostgreSQL";
            case MSSQL -> "Microsoft SQL Server";
            case SQLITE -> "SQLite";
        };
    }

    public boolean isEmbedded() {
        return this == SQLITE;
    }
}
