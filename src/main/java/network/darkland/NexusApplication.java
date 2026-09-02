package network.darkland;

import network.darkland.Influxdb.InfluxDBManager;
import network.darkland.mongo.MongoManager;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.ProtocolHandler;
import network.darkland.redis.RedisDataContainer;
import network.darkland.redis.RedisManager;

import javax.swing.*;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

public class NexusApplication {

    private static final Logger LOGGER = Logger.getLogger(NexusApplication.class.getName());

    private static volatile NexusApplication application;

    private final RedisManager redisManager;
    private final ProtocolHandler protocolHandler;
    private final RedisDataContainer dataContainer;
    private final MongoManager mongoManager;
    private final InfluxDBManager influxDBManager;

    public NexusApplication(
            String redisHost,
            int redisPort,
            String redisUser,
            String redisPass,
            String mongoUri,
            boolean isMetricsEnabled,
            String influxUrl,
            String influxToken,
            String influxOrg,
            String influxBucket
    ) {
        application = this;

        this.redisManager    = new RedisManager(this, redisHost, redisPort, redisUser, redisPass);
        this.protocolHandler = new ProtocolHandler();
        this.dataContainer   = new RedisDataContainer();
        this.mongoManager    = new MongoManager(mongoUri);

        this.redisManager.processTask(() -> {
            if (!mongoManager.verifyConnection()) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                null,
                                "MongoDB connection failed!",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
                System.exit(1);
            }
        });

        this.redisManager.scheduleTask(() -> {
            if (!mongoManager.verifyConnection()) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                null,
                                "MongoDB connection lost!",
                                "Critical Error",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
                System.exit(1);
            }
        }, 10, 10, TimeUnit.SECONDS);

        loadAddons();

        List<String> names = protocolHandler.getAddondsNames();
        LOGGER.info("Loaded Addons: " + (names.isEmpty() ? "None" : String.join(", ", names)));

        this.influxDBManager = isMetricsEnabled
                ? new InfluxDBManager(influxUrl, influxToken.toCharArray(), influxOrg, influxBucket)
                : null;

        // Uygulama kapanırken InfluxDB buffer'ının düzgün flush edilmesi için.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (this.influxDBManager != null) {
                this.influxDBManager.close();
            }
            this.redisManager.shutdown();
        }, "Nexus-Shutdown-Hook"));
    }

    public NexusApplication(
            String redisHost,
            String mongoUri,
            boolean isMetricsEnabled,
            String influxUrl,
            String influxToken,
            String influxOrg,
            String influxBucket
    ) {
        this(redisHost, 6379, null, null, mongoUri, isMetricsEnabled,
                influxUrl, influxToken, influxOrg, influxBucket);
    }

    private void loadAddons() {
        try {
            File addonFolder = new File("addons");

            if (!addonFolder.exists()) {
                if (!addonFolder.mkdirs()) {
                    LOGGER.severe("Addon klasörü oluşturulamadı: "
                            + addonFolder.getAbsolutePath());
                    return;
                }

                LOGGER.info("Addon klasörü oluşturuldu: "
                        + addonFolder.getAbsolutePath());
                return;
            }

            if (!addonFolder.isDirectory()) {
                LOGGER.severe("Addon yolu bir klasör değil: "
                        + addonFolder.getAbsolutePath());
                return;
            }

            File[] files = addonFolder.listFiles(
                    (dir, name) -> name.toLowerCase().endsWith(".jar")
            );

            if (files == null) {
                LOGGER.warning("Addon klasörü okunamadı: "
                        + addonFolder.getAbsolutePath());
                return;
            }

            LOGGER.info("Addon klasörü: "
                    + addonFolder.getAbsolutePath());

            for (File file : files) {
                loadJar(file);
            }

        } catch (Exception e) {
            LOGGER.log(
                    java.util.logging.Level.SEVERE,
                    "Addon klasörü yüklenirken hata",
                    e
            );
        }
    }

    private void loadJar(File file) {
        try (JarFile jarFile = new JarFile(file);
             URLClassLoader classLoader = new URLClassLoader(
                     new URL[]{ file.toURI().toURL() },
                     this.getClass().getClassLoader()
             )
        ) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;

                String className = entry.getName()
                        .substring(0, entry.getName().length() - 6)
                        .replace('/', '.');

                try {
                    Class<?> clazz = classLoader.loadClass(className);

                    if (DataAddon.class.isAssignableFrom(clazz)
                            && !clazz.isInterface()
                            && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {

                        DataAddon addon = (DataAddon) clazz.getDeclaredConstructor().newInstance();
                        this.protocolHandler.registerAddon(addon);
                    }

                } catch (Throwable t) {
                    LOGGER.fine("Sınıf atlandı: " + className + " — " + t.getMessage());
                }
            }

        } catch (Exception e) {
            LOGGER.warning("JAR yüklenemedi: " + file.getName() + " — " + e.getMessage());
        }
    }

    public ProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public RedisDataContainer getDataContainer() {
        return dataContainer;
    }

    public MongoManager getMongoManager() {
        return mongoManager;
    }

    public static NexusApplication getApplication() {
        return application;
    }

    public int getDataSize() {
        return dataContainer.getDataSize();
    }

    public int getAddonSize() {
        return protocolHandler.getAddondsNames().size();
    }

    public Optional<InfluxDBManager> getInfluxDBManager() {
        return Optional.ofNullable(influxDBManager);
    }
}