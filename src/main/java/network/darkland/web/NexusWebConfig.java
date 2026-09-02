package network.darkland.web;

import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;

public class NexusWebConfig {

    private static final Path CONFIG_PATH = Path.of("config.json");

    public final String redisHost;
    public final int redisPort;
    public final String redisUser;
    public final String redisPass;
    public final String mongoUri;
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
        this.mongoUri  = cfg.optString("mongoUri", "mongodb://localhost:27017");
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
