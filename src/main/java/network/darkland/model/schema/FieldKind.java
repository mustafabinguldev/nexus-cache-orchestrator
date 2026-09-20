package network.darkland.model.schema;

import java.util.Locale;
import java.util.Map;

/**
 * Field types a JSON model definition can declare. Scalars mirror the primitive
 * field types a Java addon annotates with {@code @DbDataModels}; OBJECT / LIST / MAP
 * cover nested types, collections and keyed collections.
 */
public enum FieldKind {

    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    OBJECT,
    LIST,
    MAP,
    ANY;

    private static final Map<String, FieldKind> ALIASES = Map.ofEntries(
            Map.entry("string",  STRING),
            Map.entry("text",    STRING),
            Map.entry("str",     STRING),
            Map.entry("uuid",    STRING),
            Map.entry("int",     INT),
            Map.entry("integer", INT),
            Map.entry("long",    LONG),
            Map.entry("double",  DOUBLE),
            Map.entry("float",   DOUBLE),
            Map.entry("number",  DOUBLE),
            Map.entry("boolean", BOOLEAN),
            Map.entry("bool",    BOOLEAN),
            Map.entry("object",  OBJECT),
            Map.entry("class",   OBJECT),
            Map.entry("list",    LIST),
            Map.entry("array",   LIST),
            Map.entry("set",     LIST),
            Map.entry("map",     MAP),
            Map.entry("dict",    MAP),
            Map.entry("any",     ANY),
            Map.entry("json",    ANY)
    );

    /** Resolves a declared type name, or {@code null} when it is not a built-in kind. */
    public static FieldKind lookup(String raw) {
        if (raw == null) return null;
        return ALIASES.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isScalar() {
        return this == STRING || this == INT || this == LONG || this == DOUBLE || this == BOOLEAN;
    }

    public boolean isNumeric() {
        return this == INT || this == LONG || this == DOUBLE;
    }

    /**
     * Java type used for the id field, so handlers that read the id through
     * {@code addon.getIdClassName()} keep working exactly as with Java addons.
     */
    public Class<?> javaType() {
        return switch (this) {
            case STRING  -> String.class;
            case INT     -> Integer.class;
            case LONG    -> Long.class;
            case DOUBLE  -> Double.class;
            case BOOLEAN -> Boolean.class;
            default      -> Object.class;
        };
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
