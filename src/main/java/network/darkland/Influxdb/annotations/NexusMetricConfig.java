package network.darkland.Influxdb.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NexusMetricConfig {
    boolean enabled() default true;
    String customMeasurement() default "";
}