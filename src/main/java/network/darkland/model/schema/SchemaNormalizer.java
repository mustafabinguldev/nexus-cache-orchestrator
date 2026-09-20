package network.darkland.model.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Turns an arbitrary incoming document into a document that matches a {@link ModelType}:
 * every declared field present, defaults filled in, values coerced to the declared kind,
 * undeclared keys dropped. This is what {@code DataAddon#modelInit}/{@code modelInitComp}
 * do through reflection for Java addons.
 *
 * <p>Merge rules — OBJECT fields merge field by field over their defaults, LIST fields are
 * replaced wholesale (each element normalized), MAP fields are replaced unless the field
 * declares {@code "merge": true}, in which case entries are merged over the default map.</p>
 */
public final class SchemaNormalizer {

    private static final Logger LOGGER = Logger.getLogger(SchemaNormalizer.class.getName());
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Sink for "value did not fit the schema, default used" notices. */
    public interface Warn {
        Warn NONE = message -> { };
        void accept(String message);
    }

    private final Warn warn;

    public SchemaNormalizer(String context) {
        this.warn = message -> LOGGER.fine("[" + context + "] " + message);
    }

    public ObjectNode apply(ModelType type, JsonNode input) {
        return apply(type, input, warn);
    }

    public JsonNode coerceField(SchemaField field, JsonNode input) {
        return coerce(field, input, warn);
    }

    static ObjectNode apply(ModelType type, JsonNode input, Warn warn) {
        ObjectNode output = NODES.objectNode();
        boolean hasInput = input != null && input.isObject();

        for (SchemaField field : type.fields().values()) {
            JsonNode incoming = hasInput ? input.get(field.name()) : null;
            output.set(field.name(), coerce(field, incoming, warn));
        }
        return output;
    }

    static JsonNode coerce(SchemaField field, JsonNode input, Warn warn) {
        if (input == null || input.isNull()) return field.defaultValue().deepCopy();

        return switch (field.kind()) {
            case ANY     -> input.deepCopy();
            case STRING  -> asString(field, input, warn);
            case INT     -> asInt(field, input, warn);
            case LONG    -> asLong(field, input, warn);
            case DOUBLE  -> asDouble(field, input, warn);
            case BOOLEAN -> asBoolean(field, input, warn);
            case OBJECT  -> asObject(field, input, warn);
            case LIST    -> asList(field, input, warn);
            case MAP     -> asMap(field, input, warn);
        };
    }

    private static JsonNode asString(SchemaField field, JsonNode input, Warn warn) {
        if (input.isTextual()) return NODES.textNode(input.textValue());
        if (input.isValueNode()) return NODES.textNode(input.asText());
        return reject(field, input, warn);
    }

    private static JsonNode asInt(SchemaField field, JsonNode input, Warn warn) {
        if (input.isNumber()) return NODES.numberNode(input.intValue());
        if (input.isBoolean()) return NODES.numberNode(input.booleanValue() ? 1 : 0);
        if (input.isTextual()) {
            try {
                return NODES.numberNode(Integer.parseInt(input.textValue().trim()));
            } catch (NumberFormatException ignored) {
                // falls through to reject()
            }
        }
        return reject(field, input, warn);
    }

    private static JsonNode asLong(SchemaField field, JsonNode input, Warn warn) {
        if (input.isNumber()) return NODES.numberNode(input.longValue());
        if (input.isBoolean()) return NODES.numberNode(input.booleanValue() ? 1L : 0L);
        if (input.isTextual()) {
            try {
                return NODES.numberNode(Long.parseLong(input.textValue().trim()));
            } catch (NumberFormatException ignored) {
                // falls through to reject()
            }
        }
        return reject(field, input, warn);
    }

    private static JsonNode asDouble(SchemaField field, JsonNode input, Warn warn) {
        if (input.isNumber()) return NODES.numberNode(input.doubleValue());
        if (input.isTextual()) {
            try {
                return NODES.numberNode(Double.parseDouble(input.textValue().trim()));
            } catch (NumberFormatException ignored) {
                // falls through to reject()
            }
        }
        return reject(field, input, warn);
    }

    private static JsonNode asBoolean(SchemaField field, JsonNode input, Warn warn) {
        if (input.isBoolean()) return NODES.booleanNode(input.booleanValue());
        if (input.isNumber())  return NODES.booleanNode(input.doubleValue() != 0.0d);
        if (input.isTextual()) {
            String text = input.textValue().trim();
            if (text.equalsIgnoreCase("true"))  return NODES.booleanNode(true);
            if (text.equalsIgnoreCase("false")) return NODES.booleanNode(false);
        }
        return reject(field, input, warn);
    }

    private static JsonNode asObject(SchemaField field, JsonNode input, Warn warn) {
        if (!input.isObject()) return reject(field, input, warn);

        ObjectNode merged = (ObjectNode) field.defaultValue().deepCopy();
        for (SchemaField nested : field.objectType().fields().values()) {
            JsonNode incoming = input.get(nested.name());
            if (incoming != null && !incoming.isNull()) {
                merged.set(nested.name(), coerce(nested, incoming, warn));
            } else if (!merged.has(nested.name())) {
                merged.set(nested.name(), nested.defaultValue().deepCopy());
            }
        }
        return merged;
    }

    private static JsonNode asList(SchemaField field, JsonNode input, Warn warn) {
        if (!input.isArray()) return reject(field, input, warn);

        ArrayNode output = NODES.arrayNode(input.size());
        for (JsonNode element : input) {
            output.add(coerce(field.element(), element, warn));
        }
        return output;
    }

    private static JsonNode asMap(SchemaField field, JsonNode input, Warn warn) {
        if (!input.isObject()) return reject(field, input, warn);

        ObjectNode output = field.mergeEntries()
                ? (ObjectNode) field.defaultValue().deepCopy()
                : NODES.objectNode();

        Iterator<Map.Entry<String, JsonNode>> entries = input.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!keyMatches(field.keyKind(), entry.getKey())) {
                warn.accept("map key '" + entry.getKey() + "' is not a " + field.keyKind().lowerName()
                        + " (" + field.path() + "), entry skipped");
                continue;
            }
            output.set(entry.getKey(), coerce(field.element(), entry.getValue(), warn));
        }
        return output;
    }

    private static boolean keyMatches(FieldKind keyKind, String key) {
        if (keyKind == FieldKind.STRING) return true;
        try {
            switch (keyKind) {
                case INT     -> Integer.parseInt(key);
                case LONG    -> Long.parseLong(key);
                case DOUBLE  -> Double.parseDouble(key);
                case BOOLEAN -> { return key.equalsIgnoreCase("true") || key.equalsIgnoreCase("false"); }
                default      -> { return true; }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static JsonNode reject(SchemaField field, JsonNode input, Warn warn) {
        warn.accept("value for '" + field.path() + "' is not a " + field.kind().lowerName()
                + " (got " + input.getNodeType() + "), default used");
        return field.defaultValue().deepCopy();
    }
}
