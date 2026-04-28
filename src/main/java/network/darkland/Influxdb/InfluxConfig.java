package network.darkland.Influxdb;

public record InfluxConfig(String url, String token, String org, String bucket) {}