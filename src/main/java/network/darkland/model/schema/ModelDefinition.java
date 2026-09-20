package network.darkland.model.schema;

/**
 * A complete model definition file: everything a {@code DataAddon} needs to answer
 * for, read from JSON instead of from Java code and annotations.
 *
 * @param sourceFile        file the definition was read from, used in log messages
 * @param addonId           protocol id, unique across every addon and model
 * @param addonName         display name shown on the dashboard
 * @param cacheKeyHeaderTag cache key prefix ({@code prefix_id})
 * @param namespace         Mongo database / SQL grouping
 * @param dataset           Mongo collection / SQL table
 * @param cacheTTL          Redis TTL in seconds
 * @param l1Cache           whether the Caffeine L1 cache is used
 * @param metrics           InfluxDB settings
 * @param access            request-type and source rules
 * @param root              the model's own type: its fields and nested types
 * @param idField           the single field marked {@code "id": true}
 */
public record ModelDefinition(
        String sourceFile,
        int addonId,
        String addonName,
        String cacheKeyHeaderTag,
        String namespace,
        String dataset,
        int cacheTTL,
        boolean l1Cache,
        MetricsConfig metrics,
        AccessRules access,
        ModelType root,
        SchemaField idField
) {

    /**
     * @param enabled     push points to InfluxDB for this model
     * @param measurement measurement name; empty means "use the addon name"
     */
    public record MetricsConfig(boolean enabled, String measurement) {

        public static final MetricsConfig DISABLED = new MetricsConfig(false, "");

        public MetricsConfig {
            measurement = measurement == null ? "" : measurement;
        }

        public String resolveMeasurement(String addonName) {
            return measurement.isEmpty() ? addonName : measurement;
        }
    }
}
