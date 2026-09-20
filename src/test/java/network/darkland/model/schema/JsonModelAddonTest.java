package network.darkland.model.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import network.darkland.protocol.RequestType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonModelAddonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The animal model: shared nested types (inner classes), a list of a nested type,
     * maps of scalars and of a nested type, and an inline anonymous type.
     */
    private static final String ANIMALS = """
            {
              "addonId": 900,
              "name": "Animals",
              "namespace": "nexus_core_db",
              "dataset": "animals",
              "cacheKeyHeaderTag": "animals",
              "cacheTTL": 120,
              "types": {
                "Traits": {
                  "types": {
                    "Bird": {
                      "canFly":   { "type": "boolean", "default": true },
                      "wingSpan": { "type": "double",  "default": 1.5 }
                    },
                    "Dog": {
                      "breed":   { "type": "string",  "default": "unknown" },
                      "goodBoy": { "type": "boolean", "default": true }
                    }
                  },
                  "fields": {
                    "bird": { "type": "Bird" },
                    "dog":  { "type": "Dog" }
                  }
                }
              },
              "fields": {
                "uuid":   { "type": "string", "id": true },
                "name":   { "type": "string", "default": "unnamed" },
                "kills":  { "type": "long",   "default": 0, "metric": true },
                "traits": { "type": "Traits" },
                "tags":   { "type": "list", "of": "string" },
                "pets":   { "type": "list", "of": "Traits.Dog" },
                "scores": { "type": "map",  "key": "string", "of": "int" },
                "petsByName": { "type": "map", "of": "Traits.Dog" },
                "meta": {
                  "type": "object",
                  "fields": {
                    "note":  { "type": "string" },
                    "level": { "type": "int", "default": 1 }
                  }
                }
              }
            }
            """;

    private static JsonModelAddon animals() {
        return new JsonModelAddon(ModelSchemaParser.parse("animals.json", ANIMALS));
    }

    private static JsonNode init(JsonModelAddon addon, String json) throws Exception {
        return MAPPER.readTree(addon.modelInit(json));
    }

    // ---------------------------------------------------------------- addon identity

    @Test
    void exposesTheDefinitionThroughTheDataAddonApi() {
        JsonModelAddon addon = animals();

        assertEquals(900, addon.addonId());
        assertEquals("Animals", addon.addonName());
        assertEquals("animals", addon.cacheKeyHeaderTag());
        assertEquals("nexus_core_db", addon.getNamespace());
        assertEquals("animals", addon.getDataset());
        assertEquals(120, addon.getCacheTTL());
        assertTrue(addon.l1CacheEnabled());
        assertEquals("uuid", addon.getIdFieldName());
        assertEquals(String.class, addon.getIdClassName());
    }

    @Test
    void derivesPrefixAndDatasetFromTheName() {
        ModelDefinition definition = ModelSchemaParser.parse("player-stats.json", """
                { "addonId": 1, "name": "Player Stats",
                  "fields": { "uuid": { "type": "string", "id": true } } }
                """);

        assertEquals("player_stats", definition.cacheKeyHeaderTag());
        assertEquals("player_stats", definition.dataset());
        assertEquals("nexus_core_db", definition.namespace());
        assertEquals(300, definition.cacheTTL());
    }

    @Test
    void idKindDecidesTheJavaTypeHandlersSee() {
        ModelDefinition definition = ModelSchemaParser.parse("scores.json", """
                { "addonId": 2, "fields": { "playerId": { "type": "long", "id": true } } }
                """);

        assertEquals(Long.class, new JsonModelAddon(definition).getIdClassName());
    }

    // ---------------------------------------------------------------- defaults

    @Test
    void fillsEveryDeclaredFieldWithItsDefault() throws Exception {
        JsonNode model = init(animals(), "{\"uuid\":\"abc\"}");

        assertEquals("abc", model.get("uuid").asText());
        assertEquals("unnamed", model.get("name").asText());
        assertEquals(0L, model.get("kills").asLong());
        assertTrue(model.get("traits").get("bird").get("canFly").asBoolean());
        assertEquals(1.5d, model.get("traits").get("bird").get("wingSpan").asDouble());
        assertEquals("unknown", model.get("traits").get("dog").get("breed").asText());
        assertTrue(model.get("tags").isArray());
        assertEquals(0, model.get("tags").size());
        assertTrue(model.get("scores").isObject());
        assertEquals(0, model.get("scores").size());
        assertEquals("", model.get("meta").get("note").asText());
        assertEquals(1, model.get("meta").get("level").asInt());
    }

    @Test
    void dropsUndeclaredFields() throws Exception {
        JsonNode model = init(animals(), "{\"uuid\":\"abc\",\"hacked\":true}");

        assertFalse(model.has("hacked"));
    }

    // ---------------------------------------------------------------- nested types

    @Test
    void mergesNestedObjectsFieldByFieldOverTheirDefaults() throws Exception {
        JsonNode model = init(animals(), """
                { "uuid": "abc", "traits": { "bird": { "wingSpan": 2.5 } } }
                """);

        assertEquals(2.5d, model.get("traits").get("bird").get("wingSpan").asDouble());
        assertTrue(model.get("traits").get("bird").get("canFly").asBoolean(), "untouched field keeps its default");
        assertEquals("unknown", model.get("traits").get("dog").get("breed").asText());
    }

    @Test
    void normalizesEveryElementOfAListOfNestedTypes() throws Exception {
        JsonNode model = init(animals(), """
                { "uuid": "abc", "pets": [ { "breed": "husky" }, { "breed": "lab", "goodBoy": false } ] }
                """);

        assertEquals(2, model.get("pets").size());
        assertEquals("husky", model.get("pets").get(0).get("breed").asText());
        assertTrue(model.get("pets").get(0).get("goodBoy").asBoolean(), "missing element field gets its default");
        assertFalse(model.get("pets").get(1).get("goodBoy").asBoolean());
    }

    @Test
    void shorthandTypeBodiesMayStillDeclareInnerTypes() throws Exception {
        JsonModelAddon addon = new JsonModelAddon(ModelSchemaParser.parse("shorthand.json", """
                {
                  "addonId": 8,
                  "name": "Shorthand",
                  "types": {
                    "Pet": {
                      "types": { "Collar": { "color": { "type": "string", "default": "red" } } },
                      "name":   "string",
                      "collar": { "type": "Collar" }
                    }
                  },
                  "fields": {
                    "uuid": { "type": "string", "id": true },
                    "pet":  { "type": "Pet" }
                  }
                }
                """));

        JsonNode model = init(addon, "{\"uuid\":\"abc\"}");

        assertEquals("red", model.get("pet").get("collar").get("color").asText());
        assertEquals("", model.get("pet").get("name").asText());
    }

    @Test
    void normalizesMapValues() throws Exception {
        JsonNode model = init(animals(), """
                { "uuid": "abc", "scores": { "wins": "5" }, "petsByName": { "rex": { "breed": "lab" } } }
                """);

        assertEquals(5, model.get("scores").get("wins").asInt());
        assertEquals("lab", model.get("petsByName").get("rex").get("breed").asText());
        assertTrue(model.get("petsByName").get("rex").get("goodBoy").asBoolean());
    }

    @Test
    void listsAndMapsAreReplacedUnlessMergeIsRequested() throws Exception {
        String definition = """
                {
                  "addonId": 3,
                  "name": "Inventory",
                  "fields": {
                    "uuid":     { "type": "string", "id": true },
                    "replaced": { "type": "map", "of": "int", "default": { "coins": 10 } },
                    "merged":   { "type": "map", "of": "int", "default": { "coins": 10 }, "merge": true },
                    "items":    { "type": "list", "of": "string", "default": ["stick"] }
                  }
                }
                """;
        JsonModelAddon addon = new JsonModelAddon(ModelSchemaParser.parse("inventory.json", definition));

        JsonNode untouched = init(addon, "{\"uuid\":\"abc\"}");
        assertEquals(10, untouched.get("replaced").get("coins").asInt());
        assertEquals(1, untouched.get("items").size());

        JsonNode updated = init(addon, """
                { "uuid": "abc", "replaced": { "gems": 1 }, "merged": { "gems": 1 }, "items": ["sword"] }
                """);

        assertFalse(updated.get("replaced").has("coins"), "a map is replaced by default");
        assertEquals(10, updated.get("merged").get("coins").asInt(), "merge:true keeps default entries");
        assertEquals(1, updated.get("merged").get("gems").asInt());
        assertEquals(List.of("sword"), List.of(updated.get("items").get(0).asText()));
    }

    // ---------------------------------------------------------------- coercion

    @Test
    void coercesScalarsAndFallsBackToTheDefaultWhenImpossible() throws Exception {
        JsonModelAddon addon = animals();

        assertEquals(7L, init(addon, "{\"uuid\":\"abc\",\"kills\":\"7\"}").get("kills").asLong());
        assertEquals(0L, init(addon, "{\"uuid\":\"abc\",\"kills\":{\"nope\":1}}").get("kills").asLong());
        assertEquals("5", init(addon, "{\"uuid\":\"abc\",\"name\":5}").get("name").asText());
        assertEquals("unnamed", init(addon, "{\"uuid\":\"abc\",\"name\":null}").get("name").asText());
    }

    @Test
    void modelInitCompProducesTheSameShapeAsModelInit() throws Exception {
        JsonModelAddon addon = animals();
        String payload = "{\"uuid\":\"abc\",\"kills\":3}";

        assertEquals(MAPPER.readTree(addon.modelInit(payload)), MAPPER.readTree(addon.modelInitComp(payload)));
    }

    @Test
    void generateRawJsonBuildsADefaultDocumentForAnId() throws Exception {
        JsonNode model = MAPPER.readTree(animals().generateRawJson("abc"));

        assertEquals("abc", model.get("uuid").asText());
        assertEquals("unnamed", model.get("name").asText());
    }

    // ---------------------------------------------------------------- access rules

    @Test
    void accessRulesGateRequestTypesAndSources() {
        JsonModelAddon addon = new JsonModelAddon(ModelSchemaParser.parse("guarded.json", """
                {
                  "addonId": 4,
                  "name": "Guarded",
                  "access": { "deny": ["RANKING"], "sources": { "REMOVE_DATA": ["admin"] } },
                  "fields": { "uuid": { "type": "string", "id": true } }
                }
                """));

        assertTrue(addon.handleRequest("lobby", RequestType.GET_DATA, null));
        assertFalse(addon.handleRequest("lobby", RequestType.RANKING, null));
        assertFalse(addon.handleRequest("lobby", RequestType.REMOVE_DATA, null));
        assertTrue(addon.handleRequest("admin", RequestType.REMOVE_DATA, null));
    }

    @Test
    void listingAllowedTypesTurnsTheModelIntoAWhitelist() {
        JsonModelAddon addon = new JsonModelAddon(ModelSchemaParser.parse("readonly.json", """
                {
                  "addonId": 5,
                  "name": "Read Only",
                  "access": { "allow": ["GET_DATA"] },
                  "fields": { "uuid": { "type": "string", "id": true } }
                }
                """));

        assertTrue(addon.handleRequest("lobby", RequestType.GET_DATA, null));
        assertFalse(addon.handleRequest("lobby", RequestType.SET_DATA, null));
    }

    // ---------------------------------------------------------------- parse failures

    @Test
    void reportsDefinitionMistakesWithFileAndPath() {
        assertMessageContains("no id", """
                { "addonId": 6, "fields": { "name": { "type": "string" } } }
                """, "id");

        assertMessageContains("two ids", """
                { "addonId": 6, "fields": { "a": { "type": "string", "id": true },
                                            "b": { "type": "string", "id": true } } }
                """, "only one id field");

        assertMessageContains("unknown type", """
                { "addonId": 6, "fields": { "uuid": { "type": "string", "id": true },
                                            "pet": { "type": "Dog" } } }
                """, "unknown type 'Dog'");

        assertMessageContains("missing addonId", """
                { "fields": { "uuid": { "type": "string", "id": true } } }
                """, "addonId");

        assertMessageContains("no type", """
                { "addonId": 6, "fields": { "uuid": { "type": "string", "id": true },
                                            "mystery": { } } }
                """, "\"type\" is required");

        assertMessageContains("object id", """
                { "addonId": 6, "fields": { "uuid": { "type": "object", "id": true,
                                            "fields": { "x": { "type": "int" } } } } }
                """, "must be a scalar type");

        assertMessageContains("metric on object", """
                { "addonId": 6, "fields": { "uuid": { "type": "string", "id": true },
                                            "box": { "type": "object", "metric": true,
                                                     "fields": { "x": { "type": "int" } } } } }
                """, "only scalar fields");

        assertMessageContains("unknown request type", """
                { "addonId": 6, "access": { "deny": ["DROP_TABLE"] },
                  "fields": { "uuid": { "type": "string", "id": true } } }
                """, "unknown request type");
    }

    @Test
    void rejectsTypeCycles() {
        assertMessageContains("cycle", """
                {
                  "addonId": 7,
                  "types": {
                    "A": { "b": { "type": "B" } },
                    "B": { "a": { "type": "A" } }
                  },
                  "fields": { "uuid": { "type": "string", "id": true },
                              "a": { "type": "A" } }
                }
                """, "type cycle detected");
    }

    private static void assertMessageContains(String label, String definition, String expected) {
        ModelSchemaException thrown = assertThrows(ModelSchemaException.class,
                () -> ModelSchemaParser.parse("broken.json", definition), label);

        assertTrue(thrown.getMessage().contains(expected),
                label + ": expected a message containing '" + expected + "' but got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().startsWith("broken.json"),
                label + ": the message should name the file, got: " + thrown.getMessage());
    }
}
