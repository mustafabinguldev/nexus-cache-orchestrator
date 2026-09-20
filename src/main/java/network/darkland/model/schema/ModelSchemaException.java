package network.darkland.model.schema;

/**
 * Thrown while a model definition file is parsed. The message always carries the
 * file and the field path so a broken definition can be fixed without a debugger.
 */
public class ModelSchemaException extends RuntimeException {

    public ModelSchemaException(String message) {
        super(message);
    }

    public ModelSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
