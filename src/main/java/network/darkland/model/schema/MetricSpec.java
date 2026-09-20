package network.darkland.model.schema;

/**
 * Per-field InfluxDB metric settings, the JSON counterpart of {@code @NexusMetric}.
 *
 * @param name custom measurement field name; empty means "use the field name"
 * @param tag  write the value as a tag as well as a field
 */
public record MetricSpec(String name, boolean tag) {

    public MetricSpec {
        name = name == null ? "" : name;
    }

    public String resolveName(String fieldName) {
        return name.isEmpty() ? fieldName : name;
    }
}
