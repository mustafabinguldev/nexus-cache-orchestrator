package network.darkland.web;

import network.darkland.NexusApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        NexusWebConfig cfg = NexusWebConfig.load();

        if (cfg == null || !cfg.isValid()) {
            if (cfg == null) {
                System.out.println("config.json not found (" + Path.of("config.json").toAbsolutePath() + ").");
            } else {
                System.out.println("config.json missing/invalid (adminUsername or adminPasswordHash is missing).");
            }

            cfg = new SetupWizard().run();

            if (cfg == null) {
                System.err.println("Installation could not be completed; exiting");
                System.exit(1);
                return;
            }
        }

        System.out.println("Nexus core application is starting up....");

        try {
            new NexusApplication(
                    cfg.redisHost, cfg.redisPort, cfg.redisUser, cfg.redisPass,
                    cfg.mongoUri, cfg.metricsEnabled,
                    cfg.influxUrl, cfg.influxToken, cfg.influxOrg, cfg.influxBucket
            );
        } catch (Exception e) {
            System.err.println("Could not start Nexus: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        Map<String, Object> springProps = new HashMap<>();
        springProps.put("server.port", cfg.webPort);
        springProps.put("nexus.admin.username", cfg.adminUsername);
        springProps.put("nexus.admin.password-hash", cfg.adminPasswordHash);
        springProps.put("spring.main.banner-mode", "off");
        springProps.put("server.error.include-message", "never");
        springProps.put("server.error.include-stacktrace", "never");

        new SpringApplicationBuilder(WebApplication.class)
                .properties(springProps)
                .run(args);

        System.out.println("Nexus Web Panel: http://0.0.0.0:" + cfg.webPort);
    }
}
