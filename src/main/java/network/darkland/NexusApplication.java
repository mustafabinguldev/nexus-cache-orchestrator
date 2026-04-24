package network.darkland;

import network.darkland.mongo.MongoManager;
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.ProtocolHandler;
import network.darkland.redis.RedisDataContainer;
import network.darkland.redis.RedisManager;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class NexusApplication {


    private RedisManager redisManager;

    private ProtocolHandler protocolHandler;

    private RedisDataContainer dataContainer;

    private MongoManager mongoManager;


    private static NexusApplication application;
    public NexusApplication(String redisHost, String mongoUri) {
        application = this;
        this.redisManager = new RedisManager(this, redisHost);
        this.protocolHandler = new ProtocolHandler();
        this.dataContainer = new RedisDataContainer();
        this.mongoManager = new MongoManager(mongoUri);


        loadAddons();

        List<String> names = protocolHandler.getAddondsNames();

        if (names.isEmpty()) {
            System.out.println("Loaded Addons: None");
        } else {
            System.out.println("Loaded Addons: " + String.join(", ", names));
        }

    }

    private void loadAddons() {
        try {
            URI jarUri = NexusApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File jarFileDir = new File(jarUri).getParentFile();
            File addonFolder = new File(jarFileDir, "addons");

            if (!addonFolder.exists()) {
                if (addonFolder.mkdirs()) {
                }
                return;
            }

            File[] files = addonFolder.listFiles((dir, name) -> name.endsWith(".jar"));
            if (files == null) return;

            for (File file : files) {
                loadJar(file);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadJar(File file) {
        try (JarFile jarFile = new JarFile(file)) {
            URL[] urls = { file.toURI().toURL() };
            URLClassLoader classLoader = new URLClassLoader(urls, this.getClass().getClassLoader());

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;

                String className = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');

                try {
                    Class<?> clazz = classLoader.loadClass(className);

                    if (DataAddon.class.isAssignableFrom(clazz) &&
                            !clazz.isInterface() &&
                            !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {

                        DataAddon addon = (DataAddon) clazz.getDeclaredConstructor().newInstance();
                        this.protocolHandler.registerAddon(addon);

                    }
                } catch (Throwable ignored) {

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        return getDataContainer().getDataSize();
    }

    public int getAddonSize() {
        return protocolHandler.getAddondsNames().size();
    }

}
