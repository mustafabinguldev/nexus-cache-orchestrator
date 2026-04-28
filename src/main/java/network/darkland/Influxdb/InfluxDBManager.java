package network.darkland.Influxdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import java.time.Instant;

public class InfluxDBManager {

    private final InfluxDBClient client;

    public InfluxDBManager(String URL, char[] token, String org, String bucket) {
        this.client = InfluxDBClientFactory.create(URL, token, org, bucket);

        sendStartupHeartbeat(URL, token, org, bucket);
    }
    public void write(Point point) {
        if (client == null) return;
        try (WriteApi writeApi = client.makeWriteApi()) {
            writeApi.writePoint(point);
        } catch (Exception e) {
            System.err.println("[InfluxDB] Write error: " + e.getMessage());
        }
    }

    public void writeSimple(String measurement, String tagKey, String tagValue, String fieldKey, Number fieldValue) {
        Point point = Point.measurement(measurement)
                .addTag(tagKey, tagValue)
                .addField(fieldKey, fieldValue)
                .time(Instant.now(), WritePrecision.NS);
        write(point);
    }

    private void sendStartupHeartbeat(String URL, char[] token, String org, String bucket) {
        Point startupPoint = Point.measurement("nexus_status")
                .addTag("event", "startup")
                .addTag("version", "1.3.1-ALPHA")
                .addField("status_code", 1)
                .time(Instant.now(), WritePrecision.NS);

        write(startupPoint);

        String maskedToken = (token != null && token.length > 8)
                ? new String(token).substring(0, 4) + "********" + new String(token).substring(token.length - 4)
                : "********";

        System.out.println("\n" +
                "      [ NEXUS METRICS SYSTEM ]\n" +
                "------------------------------------------\n" +
                "» STATUS     : Connection Established\n" +
                "» ENDPOINT   : " + URL + "\n" +
                "» ORG/BUCKET : " + org + " / " + bucket + "\n" +
                "» AUTH TOKEN : " + maskedToken + "\n" +
                "» TELEMETRY  : Initial heartbeat transmitted\n" +
                "------------------------------------------\n" +
                "NEXUS Engine is now streaming live data...\n");
    }

    public void close() {
        if (client != null) client.close();
    }
}