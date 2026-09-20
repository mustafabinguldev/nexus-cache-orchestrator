package network.darkland.model.schema;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import network.darkland.protocol.RequestType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a {@code models/*.json} definition file into a {@link ModelDefinition}.
 *
 * <p>Every failure is reported as a {@link ModelSchemaException} carrying the file name
 * and the path of the offending element, so a broken model can be fixed from the log line
 * alone. Comments and trailing commas are accepted in definition files.</p>
 */
public final class ModelSchemaParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String DEFAULT_NAMESPACE = "nexus_core_db";
    private static final int    DEFAULT_CACHE_TTL = 300;

    private final String source;

    private ModelSchemaParser(String source) {
        this.source = source;
    }

    public static ModelDefinition parse(File file) {
        String json;
        try {
            json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ModelSchemaException(file.getName() + ": could not be read — " + e.getMessage(), e);
        }
        return parse(file.getName(), json);
    }

    public static ModelDefinition parse(String source, String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ModelSchemaException(source + ": invalid JSON — " + e.getMessage(), e);
        }
        return new ModelSchemaParser(source).parseDefinition(root);
    }

    // ---------------------------------------------------------------- definition

    private ModelDefinition parseDefinition(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw error("", "the definition file must contain a JSON object");
        }

        int    addonId = requiredInt(root, "addonId", "id", "protocol", "protocolId");
        String name    = text(root, stripExtension(source), "name", "addonName");

        String cachePrefix = text(root, slug(name), "cacheKeyHeaderTag", "cachePrefix", "cacheKey");
        String namespace   = text(root, DEFAULT_NAMESPACE, "namespace", "database");
        String dataset     = text(root, slug(name), "dataset", "collection", "table");

        int     cacheTTL = intValue(root, DEFAULT_CACHE_TTL, "cacheTTL", "ttl");
        boolean l1Cache  = boolValue(root, true, "l1Cache", "l1CacheEnabled");

        requireText(cachePrefix, "cacheKeyHeaderTag");
        requireText(namespace, "namespace");
        requireText(dataset, "dataset");

        if (cacheTTL <= 0) throw error("cacheTTL", "must be a positive number of seconds");

        ModelType rootType = new ModelType(typeName(name), null);

        JsonNode typesNode = first(root, "types");
        declareTypes(rootType, typesNode, "types");

        JsonNode fieldsNode = first(root, "fields");
        if (fieldsNode == null || !fieldsNode.isObject() || fieldsNode.isEmpty()) {
            throw error("fields", "at least one field must be declared");
        }

        fillTypes(rootType, typesNode, "types");
        parseFields(rootType, fieldsNode, "fields", true);

        SchemaField idField = findIdField(rootType);
        rejectTypeCycles(rootType);

        return new ModelDefinition(
                source,
                addonId,
                name,
                cachePrefix,
                namespace,
                dataset,
                cacheTTL,
                l1Cache,
                parseMetricsConfig(first(root, "metrics")),
                parseAccess(first(root, "access", "permissions")),
                rootType,
                idField
        );
    }

    private SchemaField findIdField(ModelType rootType) {
        List<SchemaField> ids = rootType.fields().values().stream().filter(SchemaField::isId).toList();

        if (ids.isEmpty()) {
            throw error("fields", "exactly one field must be marked with \"id\": true");
        }
        if (ids.size() > 1) {
            throw error("fields", "only one id field is allowed, found: "
                    + ids.stream().map(SchemaField::name).toList());
        }
        SchemaField id = ids.get(0);
        if (!id.kind().isScalar()) {
            throw error("fields." + id.name(), "the id field must be a scalar type, not " + id.kind().lowerName());
        }
        return id;
    }

    // ---------------------------------------------------------------- types

    /** Declares every type name first, so fields may reference types defined later in the file. */
    private void declareTypes(ModelType owner, JsonNode typesNode, String path) {
        if (typesNode == null || typesNode.isNull()) return;
        if (!typesNode.isObject()) throw error(path, "must be an object of typeName -> type definition");

        typesNode.fields().forEachRemaining(entry -> {
            String typeName = entry.getKey();
            if (!IDENTIFIER.matcher(typeName).matches()) {
                throw error(path + "." + typeName, "type names must be letters, digits or underscore");
            }
            if (owner.hasNested(typeName)) {
                throw error(path + "." + typeName, "duplicate type declaration");
            }
            ModelType declared = owner.declareNested(typeName);
            declareTypes(declared, first(entry.getValue(), "types"), path + "." + typeName + ".types");
        });
    }

    private void fillTypes(ModelType owner, JsonNode typesNode, String path) {
        if (typesNode == null || typesNode.isNull()) return;

        typesNode.fields().forEachRemaining(entry -> {
            String typeName = entry.getKey();
            JsonNode body   = entry.getValue();
            ModelType type  = owner.nestedTypes().get(typeName);

            fillTypes(type, first(body, "types"), path + "." + typeName + ".types");
            parseFields(type, typeBody(body, path + "." + typeName), path + "." + typeName, false);
        });
    }

    /**
     * A type body is either {@code {"fields": {...}, "types": {...}}} or, as a shorthand,
     * the field map itself.
     */
    private JsonNode typeBody(JsonNode body, String path) {
        if (body == null || !body.isObject()) throw error(path, "a type must be a JSON object");

        JsonNode fields = first(body, "fields");
        if (fields != null) {
            if (!fields.isObject()) throw error(path + ".fields", "must be an object of fieldName -> field");
            return fields;
        }

        // Shorthand form: the body itself is the field map, minus the nested type block.
        com.fasterxml.jackson.databind.node.ObjectNode shorthand = MAPPER.createObjectNode();
        body.fields().forEachRemaining(entry -> {
            if (!"types".equals(entry.getKey())) shorthand.set(entry.getKey(), entry.getValue());
        });
        return shorthand;
    }

    private void parseFields(ModelType type, JsonNode fieldsNode, String path, boolean rootLevel) {
        if (fieldsNode == null || !fieldsNode.isObject() || fieldsNode.isEmpty()) {
            throw error(path, "at least one field must be declared");
        }

        fieldsNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            if (!IDENTIFIER.matcher(fieldName).matches()) {
                throw error(path + "." + fieldName, "field names must be letters, digits or underscore");
            }
            type.addField(parseField(type, fieldName, entry.getValue(), path + "." + fieldName, rootLevel));
        });

        if (type.fields().isEmpty()) throw error(path, "at least one field must be declared");
    }

    // ---------------------------------------------------------------- fields

    private SchemaField parseField(ModelType owner, String name, JsonNode spec, String path, boolean rootLevel) {
        if (spec == null || spec.isNull()) throw error(path, "field definition is missing");

        if (spec.isTextual()) {
            return buildField(owner, name, MAPPER.createObjectNode().put("type", spec.textValue()), path, rootLevel);
        }
        if (!spec.isObject()) {
            throw error(path, "a field must be a type name or a JSON object");
        }
        return buildField(owner, name, spec, path, rootLevel);
    }

    private SchemaField buildField(ModelType owner, String name, JsonNode spec, String path, boolean rootLevel) {
        String declaredType = textOrNull(spec, "type", "kind");
        JsonNode inlineFields = first(spec, "fields");
        JsonNode declaredDefault = first(spec, "default", "defaultValue");

        FieldKind kind;
        ModelType referenced = null;

        if (declaredType != null) {
            FieldKind builtin = FieldKind.lookup(declaredType);
            if (builtin != null) {
                kind = builtin;
            } else {
                referenced = owner.resolve(declaredType).orElseThrow(() -> error(path + ".type",
                        "unknown type '" + declaredType + "'; declare it under \"types\" or use a built-in type"));
                kind = FieldKind.OBJECT;
            }
        } else if (inlineFields != null) {
            kind = FieldKind.OBJECT;
        } else if (declaredDefault != null) {
            kind = inferKind(declaredDefault, path);
        } else {
            throw error(path, "\"type\" is required (for example \"string\", \"long\", \"list\", \"map\" "
                    + "or the name of a type declared under \"types\")");
        }

        boolean id = boolValue(spec, false, "id", "isId");
        MetricSpec metric = parseMetric(spec, path, kind, rootLevel);

        return switch (kind) {
            case OBJECT -> {
                ModelType objectType = referenced != null
                        ? referenced
                        : inlineType(owner, name, spec, path);
                yield new SchemaField(path, name, kind, declaredDefault, objectType,
                        null, null, id, false, metric);
            }
            case LIST -> {
                SchemaField element = parseElement(owner, name, spec, path, "of", "items", "element");
                yield new SchemaField(path, name, kind, declaredDefault, null,
                        element, null, id, false, metric);
            }
            case MAP -> {
                SchemaField element = parseElement(owner, name, spec, path, "of", "value", "values");
                FieldKind keyKind = parseKeyKind(spec, path);
                boolean merge = boolValue(spec, false, "merge", "mergeEntries");
                yield new SchemaField(path, name, kind, declaredDefault, null,
                        element, keyKind, id, merge, metric);
            }
            default -> {
                if (declaredDefault != null && !declaredDefault.isValueNode()) {
                    throw error(path + ".default", "a " + kind.lowerName() + " default must be a simple value");
                }
                yield new SchemaField(path, name, kind, declaredDefault, null, null, null, id, false, metric);
            }
        };
    }

    /** An inline {@code "fields": {...}} block: an anonymous type owned by this field. */
    private ModelType inlineType(ModelType owner, String name, JsonNode spec, String path) {
        JsonNode inlineFields = first(spec, "fields");
        if (inlineFields == null) {
            throw error(path, "an object field needs either a declared type name or an inline \"fields\" block");
        }

        ModelType type = new ModelType(typeName(name), owner);
        declareTypes(type, first(spec, "types"), path + ".types");
        fillTypes(type, first(spec, "types"), path + ".types");
        parseFields(type, inlineFields, path + ".fields", false);
        return type;
    }

    /** The element descriptor of a list or the value descriptor of a map. */
    private SchemaField parseElement(ModelType owner, String name, JsonNode spec, String path, String... keys) {
        JsonNode elementSpec = first(spec, keys);
        String elementPath = path + "[]";

        if (elementSpec == null) {
            JsonNode inlineFields = first(spec, "fields");
            if (inlineFields != null) {
                com.fasterxml.jackson.databind.node.ObjectNode synthetic = MAPPER.createObjectNode();
                synthetic.set("fields", inlineFields);

                JsonNode inlineTypes = first(spec, "types");
                if (inlineTypes != null) synthetic.set("types", inlineTypes);

                elementSpec = synthetic;
            } else {
                elementSpec = MAPPER.createObjectNode().put("type", "any");
            }
        }

        SchemaField element = parseField(owner, name, elementSpec, elementPath, false);
        if (element.isId()) throw error(elementPath, "list and map elements cannot be the id field");
        return element;
    }

    private FieldKind parseKeyKind(JsonNode spec, String path) {
        String declared = textOrNull(spec, "key", "keyType");
        if (declared == null) return FieldKind.STRING;

        FieldKind kind = FieldKind.lookup(declared);
        if (kind == null || !kind.isScalar()) {
            throw error(path + ".key", "map keys must be a scalar type (string, int, long, double, boolean)");
        }
        return kind;
    }

    private FieldKind inferKind(JsonNode defaultNode, String path) {
        if (defaultNode.isTextual())        return FieldKind.STRING;
        if (defaultNode.isBoolean())        return FieldKind.BOOLEAN;
        if (defaultNode.isIntegralNumber()) return FieldKind.LONG;
        if (defaultNode.isFloatingPointNumber()) return FieldKind.DOUBLE;
        if (defaultNode.isArray())          return FieldKind.LIST;
        if (defaultNode.isObject())         return FieldKind.MAP;
        throw error(path, "\"type\" is required; it cannot be inferred from the given default");
    }

    private MetricSpec parseMetric(JsonNode spec, String path, FieldKind kind, boolean rootLevel) {
        JsonNode node = first(spec, "metric");
        if (node == null || node.isNull()) return null;

        boolean enabled;
        String name = "";
        boolean tag = false;

        if (node.isBoolean()) {
            enabled = node.booleanValue();
        } else if (node.isTextual()) {
            enabled = true;
            name = node.textValue();
        } else if (node.isObject()) {
            enabled = boolValue(node, true, "enabled");
            name    = text(node, "", "name", "value");
            tag     = boolValue(node, false, "tag", "isTag");
        } else {
            throw error(path + ".metric", "must be true/false, a name, or an object");
        }

        if (!enabled) return null;
        if (!rootLevel) throw error(path + ".metric", "metrics are only supported on top-level fields");
        if (!kind.isScalar()) {
            throw error(path + ".metric", "only scalar fields can be written to InfluxDB, not " + kind.lowerName());
        }
        return new MetricSpec(name, tag);
    }

    // ---------------------------------------------------------------- metrics / access

    private ModelDefinition.MetricsConfig parseMetricsConfig(JsonNode node) {
        if (node == null || node.isNull()) return ModelDefinition.MetricsConfig.DISABLED;

        if (node.isBoolean()) {
            return new ModelDefinition.MetricsConfig(node.booleanValue(), "");
        }
        if (!node.isObject()) throw error("metrics", "must be true/false or an object");

        boolean enabled = boolValue(node, true, "enabled");
        String measurement = text(node, "", "measurement", "customMeasurement");
        return new ModelDefinition.MetricsConfig(enabled, measurement);
    }

    private AccessRules parseAccess(JsonNode node) {
        if (node == null || node.isNull()) return AccessRules.allowAll();
        if (!node.isObject()) throw error("access", "must be an object");

        Set<String> allow = requestTypes(first(node, "allow", "allowed"), "access.allow");
        Set<String> deny  = requestTypes(first(node, "deny", "denied"), "access.deny");

        JsonNode defaultNode = first(node, "default", "defaultPolicy");
        boolean allowByDefault;
        if (defaultNode == null || defaultNode.isNull()) {
            allowByDefault = allow.isEmpty();
        } else if (defaultNode.isBoolean()) {
            allowByDefault = defaultNode.booleanValue();
        } else {
            String policy = defaultNode.asText("").trim().toLowerCase(Locale.ROOT);
            if (policy.equals("allow"))      allowByDefault = true;
            else if (policy.equals("deny"))  allowByDefault = false;
            else throw error("access.default", "must be \"allow\" or \"deny\"");
        }

        return new AccessRules(allowByDefault, allow, deny, parseSources(first(node, "sources")));
    }

    private Map<String, Set<String>> parseSources(JsonNode node) {
        Map<String, Set<String>> sources = new HashMap<>();
        if (node == null || node.isNull()) return sources;

        if (node.isArray() || node.isTextual()) {
            sources.put("*", sourceList(node, "access.sources"));
            return sources;
        }
        if (!node.isObject()) throw error("access.sources", "must be an object, an array or a single source name");

        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey().trim();
            String typeKey = "*".equals(key) ? "*" : requestType(key, "access.sources." + key);
            sources.put(typeKey, sourceList(entry.getValue(), "access.sources." + key));
        });
        return sources;
    }

    private Set<String> sourceList(JsonNode node, String path) {
        Set<String> values = new LinkedHashSet<>();
        if (node.isTextual()) {
            values.add(node.textValue().trim().toLowerCase(Locale.ROOT));
            return values;
        }
        if (!node.isArray()) throw error(path, "must be a source name or an array of source names");

        for (JsonNode entry : node) {
            if (!entry.isTextual()) throw error(path, "source names must be strings");
            values.add(entry.textValue().trim().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private Set<String> requestTypes(JsonNode node, String path) {
        Set<String> types = new LinkedHashSet<>();
        if (node == null || node.isNull()) return types;

        if (node.isTextual()) {
            types.add(requestType(node.textValue(), path));
            return types;
        }
        if (!node.isArray()) throw error(path, "must be a request type or an array of request types");

        for (JsonNode entry : node) {
            if (!entry.isTextual()) throw error(path, "request types must be strings");
            types.add(requestType(entry.textValue(), path));
        }
        return types;
    }

    private String requestType(String raw, String path) {
        return RequestType.lookup(raw)
                .orElseThrow(() -> error(path, "unknown request type '" + raw + "'"))
                .key();
    }

    // ---------------------------------------------------------------- validation

    /**
     * Rejects type cycles. Defaults are materialised eagerly for every declared type,
     * so a type that (directly or indirectly) contains itself could never be built.
     */
    private void rejectTypeCycles(ModelType rootType) {
        List<ModelType> declared = new ArrayList<>();
        collectTypes(rootType, declared);

        Set<ModelType> done = new HashSet<>();
        for (ModelType type : declared) {
            visit(type, new ArrayDeque<>(), done);
        }
    }

    private void collectTypes(ModelType type, List<ModelType> out) {
        out.add(type);
        type.nestedTypes().values().forEach(nested -> collectTypes(nested, out));
    }

    private void visit(ModelType type, Deque<ModelType> stack, Set<ModelType> done) {
        if (done.contains(type)) return;

        if (stack.contains(type)) {
            List<String> path = new ArrayList<>(stack.stream().map(ModelType::qualifiedName).toList());
            java.util.Collections.reverse(path);
            path.add(type.qualifiedName());
            throw error("types", "type cycle detected (" + String.join(" -> ", path)
                    + "); a type cannot contain itself, directly or through another type");
        }

        stack.push(type);
        for (SchemaField field : type.fields().values()) {
            for (ModelType edge : edges(field)) {
                visit(edge, stack, done);
            }
        }
        stack.pop();
        done.add(type);
    }

    private List<ModelType> edges(SchemaField field) {
        List<ModelType> edges = new ArrayList<>(2);
        if (field.objectType() != null) edges.add(field.objectType());
        if (field.element() != null)    edges.addAll(edges(field.element()));
        return edges;
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode first(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) return value;
        }
        return null;
    }

    private String text(JsonNode node, String fallback, String... keys) {
        JsonNode value = first(node, keys);
        return value == null ? fallback : value.asText(fallback).trim();
    }

    private String textOrNull(JsonNode node, String... keys) {
        JsonNode value = first(node, keys);
        return value == null || !value.isTextual() ? null : value.textValue().trim();
    }

    private int intValue(JsonNode node, int fallback, String... keys) {
        JsonNode value = first(node, keys);
        if (value == null) return fallback;
        if (!value.isNumber()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException e) {
                throw error(keys[0], "must be a number");
            }
        }
        return value.intValue();
    }

    private int requiredInt(JsonNode node, String... keys) {
        if (first(node, keys) == null) throw error(keys[0], "is required");
        return intValue(node, 0, keys);
    }

    private boolean boolValue(JsonNode node, boolean fallback, String... keys) {
        JsonNode value = first(node, keys);
        return value == null ? fallback : value.asBoolean(fallback);
    }

    private void requireText(String value, String path) {
        if (value == null || value.isBlank()) throw error(path, "must not be empty");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** {@code "Player Stats"} -> {@code "player_stats"}: a safe cache prefix / table name. */
    private static String slug(String value) {
        String slug = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return slug.replaceAll("^_+|_+$", "");
    }

    /** {@code "player stats"} -> {@code "PlayerStats"}: a readable type name for logs. */
    private static String typeName(String value) {
        StringBuilder out = new StringBuilder();
        for (String part : value.trim().split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.isEmpty() ? "Model" : out.toString();
    }

    private ModelSchemaException error(String path, String message) {
        return new ModelSchemaException(source + (path.isEmpty() ? "" : ": " + path) + ": " + message);
    }
}
