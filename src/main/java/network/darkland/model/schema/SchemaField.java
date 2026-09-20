package network.darkland.model.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One declared field of a model type.
 *
 * <p>The default value is resolved lazily: an OBJECT field's default is its type's
 * default document with the declared overlay applied on top, and that can only be
 * computed once every type in the file has been parsed.</p>
 */
public final class SchemaField {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String path;
    private final String name;
    private final FieldKind kind;
    private final JsonNode declaredDefault;
    private final ModelType objectType;
    private final SchemaField element;
    private final FieldKind keyKind;
    private final boolean id;
    private final boolean mergeEntries;
    private final MetricSpec metric;

    private volatile JsonNode resolvedDefault;

    SchemaField(String path,
                String name,
                FieldKind kind,
                JsonNode declaredDefault,
                ModelType objectType,
                SchemaField element,
                FieldKind keyKind,
                boolean id,
                boolean mergeEntries,
                MetricSpec metric) {
        this.path            = path;
        this.name            = name;
        this.kind            = kind;
        this.declaredDefault = declaredDefault;
        this.objectType      = objectType;
        this.element         = element;
        this.keyKind         = keyKind;
        this.id              = id;
        this.mergeEntries    = mergeEntries;
        this.metric          = metric;
    }

    public String path()          { return path; }
    public String name()          { return name; }
    public FieldKind kind()       { return kind; }
    public ModelType objectType() { return objectType; }
    public SchemaField element()  { return element; }
    public FieldKind keyKind()    { return keyKind; }
    public boolean isId()         { return id; }
    public boolean mergeEntries() { return mergeEntries; }
    public MetricSpec metric()    { return metric; }

    /**
     * The default value for this field. Never null and never mutated in place —
     * callers that write into the result must {@code deepCopy()} first.
     */
    public JsonNode defaultValue() {
        JsonNode cached = resolvedDefault;
        if (cached != null) return cached;

        JsonNode computed = computeDefault();
        resolvedDefault = computed;
        return computed;
    }

    private JsonNode computeDefault() {
        return switch (kind) {
            case STRING  -> declaredDefault != null ? NODES.textNode(declaredDefault.asText()) : NODES.textNode("");
            case INT     -> NODES.numberNode(declaredDefault != null ? declaredDefault.asInt() : 0);
            case LONG    -> NODES.numberNode(declaredDefault != null ? declaredDefault.asLong() : 0L);
            case DOUBLE  -> NODES.numberNode(declaredDefault != null ? declaredDefault.asDouble() : 0.0d);
            case BOOLEAN -> NODES.booleanNode(declaredDefault != null && declaredDefault.asBoolean());
            case LIST    -> listDefault();
            case MAP     -> mapDefault();
            case ANY     -> declaredDefault != null ? declaredDefault.deepCopy() : NODES.nullNode();
            case OBJECT  -> objectDefault();
        };
    }

    private JsonNode listDefault() {
        ArrayNode defaults = NODES.arrayNode();
        if (declaredDefault == null || !declaredDefault.isArray()) return defaults;

        for (JsonNode entry : declaredDefault) {
            defaults.add(SchemaNormalizer.coerce(element, entry, SchemaNormalizer.Warn.NONE));
        }
        return defaults;
    }

    private JsonNode mapDefault() {
        ObjectNode defaults = NODES.objectNode();
        if (declaredDefault == null || !declaredDefault.isObject()) return defaults;

        declaredDefault.fields().forEachRemaining(entry -> defaults.set(entry.getKey(),
                SchemaNormalizer.coerce(element, entry.getValue(), SchemaNormalizer.Warn.NONE)));
        return defaults;
    }

    private JsonNode objectDefault() {
        ObjectNode base = objectType.defaultDocument().deepCopy();
        if (declaredDefault == null || !declaredDefault.isObject()) return base;

        declaredDefault.fields().forEachRemaining(entry -> {
            SchemaField nested = objectType.fields().get(entry.getKey());
            if (nested != null) {
                base.set(entry.getKey(),
                        SchemaNormalizer.coerce(nested, entry.getValue(), SchemaNormalizer.Warn.NONE));
            }
        });
        return base;
    }

    @Override
    public String toString() {
        return path + ":" + kind.lowerName();
    }
}
