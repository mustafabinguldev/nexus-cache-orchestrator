package network.darkland.web;

import com.mongodb.MongoException;
import network.darkland.db.DatabaseType;
import network.darkland.db.DbConnectionConfig;
import network.darkland.db.mongo.MongoConnectionManager;
import network.darkland.db.sql.mariadb.MariaDbDialect;
import network.darkland.db.sql.mssql.MsSqlDialect;
import network.darkland.db.sql.mysql.MySqlDialect;
import network.darkland.db.sql.postgresql.PostgreSqlDialect;
import network.darkland.db.sql.sqlite.SqliteDialect;
import org.json.JSONObject;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SetupWizard {

    private static final Path CONFIG_PATH = Path.of("config.json");

    private final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final Console console = System.console();

    private Language lang = Language.EN;

    public enum Language {
        TR, EN
    }

    public NexusWebConfig run() {
        selectLanguage();
        printBanner();

        String redisHost = ask(msg("redis.host"), "127.0.0.1");
        int redisPort = askInt(msg("redis.port"), 6379);
        String redisUser = ask(msg("redis.user"), "");
        String redisPass = askPassword(msg("redis.pass"), true);

        testRedis(redisHost, redisPort, redisUser, redisPass);

        DatabaseType dbType = askDatabaseType();
        DbConnectionConfig dbConnectionConfig = askDbConnection(dbType);

        boolean metricsEnabled = askYesNo(msg("influx.enable"), false);
        String influxUrl = null, influxToken = null, influxOrg = null, influxBucket = null;
        if (metricsEnabled) {
            influxUrl    = ask(msg("influx.url"), "http://localhost:8086");
            influxToken  = askPassword(msg("influx.token"), false);
            influxOrg    = ask(msg("influx.org"), "");
            influxBucket = ask(msg("influx.bucket"), "");
        }

        int webPort = askInt(msg("web.port"), 8088);

        String adminUsername = ask(msg("admin.user"), "admin");
        String adminPassword = askPasswordWithConfirmation(msg("admin.pass"));

        String adminPasswordHash = new BCryptPasswordEncoder(12).encode(adminPassword);

        JSONObject cfg = new JSONObject();
        cfg.put("redisHost", redisHost);
        cfg.put("redisPort", redisPort);
        cfg.put("redisUser", redisUser);
        cfg.put("redisPass", redisPass);
        cfg.put("dbType", dbType.name());
        cfg.put("db", dbConnectionConfigToJson(dbConnectionConfig));
        cfg.put("metricsEnabled", metricsEnabled);
        if (metricsEnabled) {
            JSONObject influx = new JSONObject();
            influx.put("url", influxUrl);
            influx.put("token", influxToken);
            influx.put("org", influxOrg);
            influx.put("bucket", influxBucket);
            cfg.put("influx", influx);
        }
        cfg.put("webPort", webPort);
        cfg.put("adminUsername", adminUsername);
        cfg.put("adminPasswordHash", adminPasswordHash);

        writeConfig(cfg);

        System.out.println();
        System.out.println(msg("success.created") + CONFIG_PATH.toAbsolutePath());
        System.out.println(msg("success.bcrypt"));
        System.out.println();

        return NexusWebConfig.load();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Database engine selection
    // ────────────────────────────────────────────────────────────────────────

    private DatabaseType askDatabaseType() {
        System.out.println();
        System.out.println(msg("db.select.title"));
        System.out.println("1) MongoDB");
        System.out.println("2) MySQL");
        System.out.println("3) MariaDB");
        System.out.println("4) PostgreSQL");
        System.out.println("5) Microsoft SQL Server");
        System.out.println("6) SQLite");
        System.out.print(msg("db.select.prompt") + " [1]: ");

        String input = readLine().trim();
        return switch (input) {
            case "2" -> DatabaseType.MYSQL;
            case "3" -> DatabaseType.MARIADB;
            case "4" -> DatabaseType.POSTGRESQL;
            case "5" -> DatabaseType.MSSQL;
            case "6" -> DatabaseType.SQLITE;
            default -> DatabaseType.MONGODB;
        };
    }

    private DbConnectionConfig askDbConnection(DatabaseType type) {
        return switch (type) {
            case MONGODB -> {
                String mongoUri = ask(msg("mongo.uri"), "mongodb://localhost:27017");
                testMongo(mongoUri);
                yield DbConnectionConfig.mongo(mongoUri);
            }
            case MYSQL -> {
                MySqlDialect dialect = new MySqlDialect();
                String host = ask(msg("db.host"), "127.0.0.1");
                int port = askInt(msg("db.port"), 3306);
                String database = ask(msg("db.database"), "nexus");
                String username = ask(msg("db.user"), "root");
                String password = askPassword(msg("db.pass"), true);
                testJdbc(dialect.buildJdbcUrl(host, port, database), dialect.driverClassName(), username, password);
                yield DbConnectionConfig.mysql(host, port, database, username, password);
            }
            case MARIADB -> {
                MariaDbDialect dialect = new MariaDbDialect();
                String host = ask(msg("db.host"), "127.0.0.1");
                int port = askInt(msg("db.port"), 3306);
                String database = ask(msg("db.database"), "nexus");
                String username = ask(msg("db.user"), "root");
                String password = askPassword(msg("db.pass"), true);
                testJdbc(dialect.buildJdbcUrl(host, port, database), dialect.driverClassName(), username, password);
                yield DbConnectionConfig.mariadb(host, port, database, username, password);
            }
            case POSTGRESQL -> {
                PostgreSqlDialect dialect = new PostgreSqlDialect();
                String host = ask(msg("db.host"), "127.0.0.1");
                int port = askInt(msg("db.port"), 5432);
                String database = ask(msg("db.database"), "nexus");
                String username = ask(msg("db.user"), "postgres");
                String password = askPassword(msg("db.pass"), true);
                testJdbc(dialect.buildJdbcUrl(host, port, database), dialect.driverClassName(), username, password);
                yield DbConnectionConfig.postgresql(host, port, database, username, password);
            }
            case MSSQL -> {
                MsSqlDialect dialect = new MsSqlDialect();
                String host = ask(msg("db.host"), "127.0.0.1");
                int port = askInt(msg("db.port"), 1433);
                String database = ask(msg("db.database"), "nexus");
                String username = ask(msg("db.user"), "sa");
                String password = askPassword(msg("db.pass"), true);
                testJdbc(dialect.buildJdbcUrl(host, port, database), dialect.driverClassName(), username, password);
                yield DbConnectionConfig.mssql(host, port, database, username, password);
            }
            case SQLITE -> {
                SqliteDialect dialect = new SqliteDialect();
                String filePath = ask(msg("db.sqlite.path"), "nexus-data.db");
                testJdbc(dialect.buildJdbcUrlForFile(filePath), dialect.driverClassName(), null, null);
                yield DbConnectionConfig.sqlite(filePath);
            }
        };
    }

    private JSONObject dbConnectionConfigToJson(DbConnectionConfig config) {
        JSONObject db = new JSONObject();
        switch (config.type()) {
            case MONGODB -> db.put("mongoUri", config.mongoUri());
            case MYSQL, MARIADB, POSTGRESQL, MSSQL -> {
                db.put("host", config.host());
                db.put("port", config.port());
                db.put("database", config.database());
                db.put("username", config.username());
                db.put("password", config.password());
            }
            case SQLITE -> db.put("filePath", config.filePath());
        }
        return db;
    }

    private void selectLanguage() {
        System.out.println();
        System.out.println("Select Language / Dil Seçiniz:");
        System.out.println("1) English");
        System.out.println("2) Türkçe");
        System.out.print("Choice / Seçim [1]: ");

        String input = readLine().trim();
        if ("2".equals(input) || input.equalsIgnoreCase("tr") || input.equalsIgnoreCase("turkce")) {
            this.lang = Language.TR;
        } else {
            this.lang = Language.EN;
        }
    }

    private String msg(String key) {
        Map<String, String> tr = Map.ofEntries(
                Map.entry("banner.title", "╔══════════════════════════════════════════════╗\n║        NEXUS — İlk Kurulum Sihirbazı          ║\n╚══════════════════════════════════════════════╝"),
                Map.entry("banner.desc", "config.json bulunamadı, seni birkaç soruyla kuruluma yönlendireceğim.\nKöşeli parantez [ ] içindeki değer varsayılandır — değiştirmeden ENTER'a basarsan onu kullanır.\n"),
                Map.entry("redis.host", "Redis host"),
                Map.entry("redis.port", "Redis port"),
                Map.entry("redis.user", "Redis kullanıcı adı (ACL kullanmıyorsan boş bırak)"),
                Map.entry("redis.pass", "Redis parolası (yoksa boş bırak, ENTER'a bas)"),
                Map.entry("redis.testing", "→ Redis bağlantısı test ediliyor... "),
                Map.entry("mongo.uri", "MongoDB bağlantı URI'si"),
                Map.entry("mongo.testing", "→ MongoDB bağlantısı test ediliyor... "),
                Map.entry("db.select.title", "Hangi veritabanını kullanmak istiyorsun?"),
                Map.entry("db.select.prompt", "Seçim"),
                Map.entry("db.host", "Veritabanı host"),
                Map.entry("db.port", "Veritabanı portu"),
                Map.entry("db.database", "Veritabanı adı"),
                Map.entry("db.user", "Veritabanı kullanıcı adı"),
                Map.entry("db.pass", "Veritabanı parolası (yoksa boş bırak, ENTER'a bas)"),
                Map.entry("db.testing", "→ Veritabanı bağlantısı test ediliyor... "),
                Map.entry("db.sqlite.path", "SQLite dosya yolu"),
                Map.entry("influx.enable", "InfluxDB metrikleri etkinleştirilsin mi?"),
                Map.entry("influx.url", "InfluxDB URL"),
                Map.entry("influx.token", "InfluxDB token"),
                Map.entry("influx.org", "InfluxDB organizasyon"),
                Map.entry("influx.bucket", "InfluxDB bucket"),
                Map.entry("web.port", "Web paneli portu"),
                Map.entry("admin.user", "Panel admin kullanıcı adı"),
                Map.entry("admin.pass", "Panel admin parolası"),
                Map.entry("test.success", "BAŞARILI ✔"),
                Map.entry("test.failed", "BAŞARISIZ ✘"),
                Map.entry("test.unexpected", "BEKLENMEYEN YANIT: "),
                Map.entry("test.warn_continue", "  Bağlantı testi başarısız oldu. Yine de bu bilgilerle devam edilsin mi?"),
                Map.entry("test.cancelled", "Kurulum iptal edildi. Tekrar başlatıp doğru bilgileri gir."),
                Map.entry("err.number", "  Geçersiz sayı, tekrar dene."),
                Map.entry("err.empty_field", "  Bu alan boş bırakılamaz."),
                Map.entry("err.pass_mismatch", "  Parolalar eşleşmedi, tekrar dene."),
                Map.entry("warn.plain_pass", " (UYARI: terminal desteklenmiyor, ekranda görünecek): "),
                Map.entry("pass.confirm_suffix", " (tekrar)"),
                Map.entry("success.created", "✔ config.json oluşturuldu: "),
                Map.entry("success.bcrypt", "✔ Admin parolası BCrypt ile hashlendi, düz metin hiçbir yere yazılmadı."),
                Map.entry("err.write_config", "config.json yazılamadı: ")
        );

        Map<String, String> en = Map.ofEntries(
                Map.entry("banner.title", "╔══════════════════════════════════════════════╗\n║         NEXUS — Initial Setup Wizard         ║\n╚══════════════════════════════════════════════╝"),
                Map.entry("banner.desc", "config.json was not found. I will guide you through the setup.\nValues inside brackets [ ] are defaults — press ENTER to keep them.\n"),
                Map.entry("redis.host", "Redis host"),
                Map.entry("redis.port", "Redis port"),
                Map.entry("redis.user", "Redis username (leave empty if not using ACL)"),
                Map.entry("redis.pass", "Redis password (press ENTER if none)"),
                Map.entry("redis.testing", "→ Testing Redis connection... "),
                Map.entry("mongo.uri", "MongoDB connection URI"),
                Map.entry("mongo.testing", "→ Testing MongoDB connection... "),
                Map.entry("db.select.title", "Which database would you like to use?"),
                Map.entry("db.select.prompt", "Choice"),
                Map.entry("db.host", "Database host"),
                Map.entry("db.port", "Database port"),
                Map.entry("db.database", "Database name"),
                Map.entry("db.user", "Database username"),
                Map.entry("db.pass", "Database password (press ENTER if none)"),
                Map.entry("db.testing", "→ Testing database connection... "),
                Map.entry("db.sqlite.path", "SQLite file path"),
                Map.entry("influx.enable", "Enable InfluxDB metrics?"),
                Map.entry("influx.url", "InfluxDB URL"),
                Map.entry("influx.token", "InfluxDB token"),
                Map.entry("influx.org", "InfluxDB organization"),
                Map.entry("influx.bucket", "InfluxDB bucket"),
                Map.entry("web.port", "Web panel port"),
                Map.entry("admin.user", "Panel admin username"),
                Map.entry("admin.pass", "Panel admin password"),
                Map.entry("test.success", "SUCCESSFUL ✔"),
                Map.entry("test.failed", "FAILED ✘"),
                Map.entry("test.unexpected", "UNEXPECTED RESPONSE: "),
                Map.entry("test.warn_continue", "  Connection test failed. Do you still want to proceed with these settings?"),
                Map.entry("test.cancelled", "Setup cancelled. Restart and enter correct information."),
                Map.entry("err.number", "  Invalid number, please try again."),
                Map.entry("err.empty_field", "  This field cannot be empty."),
                Map.entry("err.pass_mismatch", "  Passwords do not match, please try again."),
                Map.entry("warn.plain_pass", " (WARNING: terminal not supported, input will be visible): "),
                Map.entry("pass.confirm_suffix", " (confirm)"),
                Map.entry("success.created", "✔ config.json created successfully: "),
                Map.entry("success.bcrypt", "✔ Admin password hashed with BCrypt, plain text was not saved."),
                Map.entry("err.write_config", "Could not write config.json: ")
        );

        return (lang == Language.TR ? tr : en).getOrDefault(key, key);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Bağlantı testleri
    // ────────────────────────────────────────────────────────────────────────

    private void testRedis(String host, int port, String user, String pass) {
        System.out.print(msg("redis.testing"));
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(1);

        boolean hasAuth = pass != null && !pass.isBlank();

        try (JedisPool pool = hasAuth
                ? new JedisPool(poolConfig, host, port, 2000,
                (user != null && !user.isBlank()) ? user : null, pass)
                : new JedisPool(poolConfig, host, port)) {

            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if ("PONG".equalsIgnoreCase(pong)) {
                    System.out.println(msg("test.success"));
                } else {
                    System.out.println(msg("test.unexpected") + pong);
                    warnAndContinue();
                }
            }
        } catch (Exception e) {
            System.out.println(msg("test.failed") + " (" + e.getMessage() + ")");
            warnAndContinue();
        }
    }


    private void testMongo(String uri) {
        System.out.print(msg("mongo.testing"));
        MongoConnectionManager tempManager = null;
        try {
            tempManager = new MongoConnectionManager(uri);
            if (tempManager.verifyConnection()) {
                System.out.println(msg("test.success"));
            } else {
                System.out.println(msg("test.failed"));
                warnAndContinue();
            }
        } catch (MongoException | IllegalArgumentException e) {
            System.out.println(msg("test.failed") + " (" + e.getMessage() + ")");
            warnAndContinue();
        } finally {
            if (tempManager != null) {
                tempManager.close();
            }
        }
    }
    private void testJdbc(String jdbcUrl, String driverClassName, String username, String password) {
        System.out.print(msg("db.testing"));
        try {
            Class.forName(driverClassName);
            try (Connection conn = (username == null)
                    ? DriverManager.getConnection(jdbcUrl)
                    : DriverManager.getConnection(jdbcUrl, username, password)) {
                if (conn.isValid(2)) {
                    System.out.println(msg("test.success"));
                } else {
                    System.out.println(msg("test.failed"));
                    warnAndContinue();
                }
            }
        } catch (Exception e) {
            System.out.println(msg("test.failed") + " (" + e.getMessage() + ")");
            warnAndContinue();
        }
    }

    private void warnAndContinue() {
        boolean proceed = askYesNo(msg("test.warn_continue"), true);
        if (!proceed) {
            System.out.println(msg("test.cancelled"));
            System.exit(1);
        }
    }

    private String ask(String label, String defaultValue) {
        String suffix = defaultValue.isEmpty() ? "" : " [" + defaultValue + "]";
        System.out.print(label + suffix + ": ");
        String line = readLine();
        return line.isBlank() ? defaultValue : line.trim();
    }

    private int askInt(String label, int defaultValue) {
        while (true) {
            String raw = ask(label, String.valueOf(defaultValue));
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                System.out.println(msg("err.number"));
            }
        }
    }

    private boolean askYesNo(String label, boolean defaultValue) {
        String hint = lang == Language.TR
                ? (defaultValue ? "[E/h]" : "[e/H]")
                : (defaultValue ? "[Y/n]" : "[y/N]");

        System.out.print(label + " " + hint + ": ");
        String line = readLine().trim().toLowerCase();
        if (line.isBlank()) return defaultValue;

        return line.startsWith("e") || line.startsWith("y");
    }

    private String askPassword(String label, boolean allowEmpty) {
        while (true) {
            String value;
            if (console != null) {
                char[] chars = console.readPassword(label + ": ");
                value = new String(chars);
            } else {
                System.out.print(label + msg("warn.plain_pass"));
                value = readLine();
            }
            if (!value.isBlank() || allowEmpty) return value;
            System.out.println(msg("err.empty_field"));
        }
    }

    private String askPasswordWithConfirmation(String label) {
        while (true) {
            String first = askPassword(label, false);
            String second = askPassword(label + msg("pass.confirm_suffix"), false);
            if (first.equals(second)) return first;
            System.out.println(msg("err.pass_mismatch"));
        }
    }

    private String readLine() {
        try {
            String line = stdin.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }

    private void writeConfig(JSONObject cfg) {
        try {
            Files.write(CONFIG_PATH,
                    cfg.toString(2).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                );
                Files.setPosixFilePermissions(CONFIG_PATH, perms);
            } catch (UnsupportedOperationException ignored) {

            }

        } catch (IOException e) {
            System.err.println(msg("err.write_config") + e.getMessage());
            System.exit(1);
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println(msg("banner.title"));
        System.out.println(msg("banner.desc"));
    }
}