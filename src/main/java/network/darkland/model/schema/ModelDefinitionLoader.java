package network.darkland.model.schema;

import network.darkland.protocol.ProtocolHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads every {@code *.json} model definition from the {@code models/} folder — the
 * data-only counterpart of the {@code addons/} folder — and registers each one as a
 * {@link JsonModelAddon}.
 *
 * <p>A broken definition never stops the rest: the file is reported with the exact
 * problem and skipped.</p>
 */
public final class ModelDefinitionLoader {

    public static final String DEFAULT_DIRECTORY = "models";

    private static final Logger LOGGER = Logger.getLogger(ModelDefinitionLoader.class.getName());

    private ModelDefinitionLoader() {
    }

    /** Parses the folder's definitions. Invalid files are logged and left out. */
    public static List<JsonModelAddon> load(File directory) {
        List<JsonModelAddon> models = new ArrayList<>();

        File[] files = definitionFiles(directory);
        if (files == null) return models;

        Map<Integer, String> idOwners = new HashMap<>();
        Map<String, String> prefixOwners = new HashMap<>();

        for (File file : files) {
            try {
                ModelDefinition definition = ModelSchemaParser.parse(file);

                String idOwner = idOwners.putIfAbsent(definition.addonId(), file.getName());
                if (idOwner != null) {
                    LOGGER.severe("[models] " + file.getName() + ": addonId " + definition.addonId()
                            + " is already used by " + idOwner + ", model skipped");
                    continue;
                }

                String prefix = definition.cacheKeyHeaderTag().toLowerCase(Locale.ROOT);
                String prefixOwner = prefixOwners.putIfAbsent(prefix, file.getName());
                if (prefixOwner != null) {
                    LOGGER.severe("[models] " + file.getName() + ": cacheKeyHeaderTag '"
                            + definition.cacheKeyHeaderTag() + "' is already used by " + prefixOwner
                            + ", model skipped");
                    idOwners.remove(definition.addonId());
                    continue;
                }

                models.add(new JsonModelAddon(definition));

            } catch (ModelSchemaException e) {
                LOGGER.severe("[models] " + e.getMessage());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[models] " + file.getName() + ": could not be loaded", e);
            }
        }
        return models;
    }

    /**
     * Loads the folder and registers every valid model with the protocol handler.
     * Models whose id clashes with an already registered addon are reported and skipped.
     *
     * @return the models that were registered
     */
    public static List<JsonModelAddon> loadAndRegister(ProtocolHandler protocolHandler, File directory) {
        List<JsonModelAddon> registered = new ArrayList<>();

        for (JsonModelAddon model : load(directory)) {
            try {
                protocolHandler.registerAddon(model);
                registered.add(model);
                LOGGER.info("[models] " + model.definition().sourceFile() + " -> "
                        + model.addonName() + " (id=" + model.addonId()
                        + ", prefix=" + model.cacheKeyHeaderTag()
                        + ", dataset=" + model.getDataset() + ")");
            } catch (RuntimeException e) {
                LOGGER.severe("[models] " + model.definition().sourceFile()
                        + ": could not be registered — " + e.getMessage());
            }
        }
        return registered;
    }

    private static File[] definitionFiles(File directory) {
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                LOGGER.severe("[models] Could not create the model folder: " + directory.getAbsolutePath());
                return null;
            }
            LOGGER.info("[models] Model folder created: " + directory.getAbsolutePath());
            return new File[0];
        }

        if (!directory.isDirectory()) {
            LOGGER.severe("[models] The model path is not a folder: " + directory.getAbsolutePath());
            return null;
        }

        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            LOGGER.warning("[models] The model folder could not be read: " + directory.getAbsolutePath());
            return null;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
}
