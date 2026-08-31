package network.darkland.Influxdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import java.time.Instant;

public class InfluxDBManager {

    private static final String VERSION = "1.4.2";

    private final InfluxDBClient client;

    private final WriteApi writeApi;

    public InfluxDBManager(String URL, char[] token, String org, String bucket) {
        this.client = InfluxDBClientFactory.create(URL, token, org, bucket);
        this.writeApi = client.makeWriteApi();

        sendStartupHeartbeat(URL, token, org, bucket);
    }

    public void write(Point point) {
        if (client == null || writeApi == null) return;
        try {
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
                .addTag("version", VERSION)
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
        if (writeApi != null) writeApi.close();
        if (client != null) client.close();
    }
}