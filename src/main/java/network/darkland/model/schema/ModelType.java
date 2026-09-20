package network.darkland.model.schema;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A type declared in a model definition — the JSON equivalent of a class in a Java
 * addon. Types can be nested inside other types exactly like inner classes, and a
 * nested type is resolvable by its simple name from anywhere inside its enclosing
 * type, or by a dotted path ({@code Pet.Collar}) from outside.
 */
public final class ModelType {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String name;
    private final ModelType enclosing;
    private final Map<String, ModelType> nested = new LinkedHashMap<>();
    private final Map<String, SchemaField> fields = new LinkedHashMap<>();

    private volatile ObjectNode defaultDocument;

    ModelType(String name, ModelType enclosing) {
        this.name      = name;
        this.enclosing = enclosing;
    }

    public String name() {
        return name;
    }

    public String qualifiedName() {
        return enclosing == null ? name : enclosing.qualifiedName() + "." + name;
    }

    public ModelType enclosing() {
        return enclosing;
    }

    public Map<String, SchemaField> fields() {
        return Collections.unmodifiableMap(fields);
    }

    public Map<String, ModelType> nestedTypes() {
        return Collections.unmodifiableMap(nested);
    }

    public Optional<SchemaField> field(String fieldName) {
        return Optional.ofNullable(fields.get(fieldName));
    }

    /**
     * Resolves a type reference the way Java resolves an inner class name: the
     * current type first, then every enclosing scope outwards. Dotted references
     * walk down the nested types of whichever scope matched first.
     */
    public Optional<ModelType> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();

        String[] parts = reference.trim().split("\\.");

        for (ModelType scope = this; scope != null; scope = scope.enclosing) {
            ModelType current = scope.name.equals(parts[0]) ? scope : scope.nested.get(parts[0]);
            if (current == null) continue;

            boolean matched = true;
            for (int i = 1; i < parts.length; i++) {
                current = current.nested.get(parts[i]);
                if (current == null) {
                    matched = false;
                    break;
                }
            }
            if (matched) return Optional.of(current);
        }
        return Optional.empty();
    }

    /** Document holding every declared field at its default value. Cached; copy before mutating. */
    public ObjectNode defaultDocument() {
        ObjectNode cached = defaultDocument;
        if (cached != null) return cached;

        ObjectNode document = NODES.objectNode();
        for (SchemaField field : fields.values()) {
            document.set(field.name(), field.defaultValue().deepCopy());
        }
        defaultDocument = document;
        return document;
    }

    ModelType declareNested(String nestedName) {
        ModelType type = new ModelType(nestedName, this);
        nested.put(nestedName, type);
        return type;
    }

    boolean hasNested(String nestedName) {
        return nested.containsKey(nestedName);
    }

    void addField(SchemaField field) {
        fields.put(field.name(), field);
    }

    @Override
    public String toString() {
        return qualifiedName() + fields.keySet();
    }
}
