package network.darkland.web;

import com.mongodb.MongoException;
import network.darkland.mongo.MongoManager;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        String mongoUri = ask(msg("mongo.uri"), "mongodb://localhost:27017");
        testMongo(mongoUri);

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
        cfg.put("mongoUri", mongoUri);
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
        MongoManager tempManager = null;
        ExecutorService tempExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            tempManager = new MongoManager(uri, tempExecutor);
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
            tempExecutor.shutdown();
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