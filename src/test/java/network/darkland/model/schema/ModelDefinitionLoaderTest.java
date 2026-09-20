package network.darkland.model.schema;

import network.darkland.protocol.ProtocolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDefinitionLoaderTest {

    @Test
    void loadsValidModelsAndSkipsBrokenOnes(@TempDir Path folder) throws Exception {
        write(folder, "good.json", """
                { "addonId": 801, "name": "Good", "fields": { "uuid": { "type": "string", "id": true } } }
                """);
        write(folder, "broken.json", """
                { "addonId": 802, "name": "Broken", "fields": { "uuid": { "type": "mystery" } } }
                """);
        write(folder, "not-a-model.txt", "ignored");

        List<JsonModelAddon> models = ModelDefinitionLoader.load(folder.toFile());

        assertEquals(1, models.size());
        assertEquals("Good", models.get(0).addonName());
    }

    @Test
    void skipsModelsThatClashOnIdOrCachePrefix(@TempDir Path folder) throws Exception {
        write(folder, "a-first.json", """
                { "addonId": 810, "name": "First", "fields": { "uuid": { "type": "string", "id": true } } }
                """);
        write(folder, "b-same-id.json", """
                { "addonId": 810, "name": "Second", "fields": { "uuid": { "type": "string", "id": true } } }
                """);
        write(folder, "c-same-prefix.json", """
                { "addonId": 811, "name": "Third", "cacheKeyHeaderTag": "first",
                  "fields": { "uuid": { "type": "string", "id": true } } }
                """);

        List<JsonModelAddon> models = ModelDefinitionLoader.load(folder.toFile());

        assertEquals(List.of("First"), models.stream().map(JsonModelAddon::addonName).toList());
    }

    @Test
    void createsTheFolderWhenItIsMissing(@TempDir Path parent) {
        File missing = parent.resolve("models").toFile();

        assertTrue(ModelDefinitionLoader.load(missing).isEmpty());
        assertTrue(missing.isDirectory(), "the models folder should be created on first start");
    }

    @Test
    void registersModelsWithTheProtocolHandlerWithoutDisturbingExistingAddons(@TempDir Path folder) throws Exception {
        write(folder, "taken.json", """
                { "addonId": 820, "name": "Taken", "fields": { "uuid": { "type": "string", "id": true } } }
                """);
        write(folder, "free.json", """
                { "addonId": 821, "name": "Free", "fields": { "uuid": { "type": "string", "id": true } } }
                """);

        ProtocolHandler handler = new ProtocolHandler();
        handler.registerAddon(new JsonModelAddon(ModelSchemaParser.parse("existing.json", """
                { "addonId": 820, "name": "Existing", "fields": { "uuid": { "type": "string", "id": true } } }
                """)));

        List<JsonModelAddon> registered = ModelDefinitionLoader.loadAndRegister(handler, folder.toFile());

        assertEquals(List.of("Free"), registered.stream().map(JsonModelAddon::addonName).toList());
        assertEquals("Existing", handler.getAddonById(820).orElseThrow().addonName());
        assertEquals("Free", handler.getAddonById(821).orElseThrow().addonName());
    }

    @Test
    void theShippedExampleIsAValidDefinition() {
        File example = new File("models/animals.json.example");
        assertTrue(example.isFile(), "models/animals.json.example is missing");

        ModelDefinition definition = ModelSchemaParser.parse(example);

        assertEquals("Animals", definition.addonName());
        assertEquals("uuid", definition.idField().name());
        assertTrue(definition.root().resolve("Traits.Dog").isPresent());
    }

    private static void write(Path folder, String name, String content) throws Exception {
        Files.writeString(folder.resolve(name), content, StandardCharsets.UTF_8);
    }
}
