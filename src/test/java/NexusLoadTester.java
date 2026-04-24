package network.darkland;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class NexusLoadTester {

    private static final String CHANNEL = "darkland_nexus";
    private static final String REDIS_HOST = "127.0.0.1";
    private static final int THREAD_COUNT = 1; // Aynı anda kaç koldan saldıralım?
    private static final int PACKET_COUNT = 1000; // Toplam kaç paket gönderilecek?

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Random random = new Random();
    private static final AtomicInteger sentCount = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(THREAD_COUNT * 2);

        try (JedisPool pool = new JedisPool(poolConfig, REDIS_HOST, 6379)) {
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

            long startTime = System.currentTimeMillis();
            System.out.println("--- NEXUS LOAD TEST STARTED ---");
            System.out.println("Targeting Channel: " + CHANNEL);
            System.out.println("Simulating " + PACKET_COUNT + " requests...");

            for (int i = 0; i < PACKET_COUNT; i++) {
                executor.execute(() -> {
                    try (Jedis jedis = pool.getResource()) {
                        // Rastgele veri üretimi
                        String randomName = "User_" + UUID.randomUUID().toString().substring(0, 8);

                        ObjectNode root = mapper.createObjectNode();
                        root.put("protocol", 500);
                        root.put("type", "GET_DATA");
                        root.put("source", "load_test_node");

                        ObjectNode data = mapper.createObjectNode();
                        data.put("name", randomName);
                        root.set("data", data);

                        // Mesajı fırlat
                        jedis.publish(CHANNEL, root.toString());

                        int current = sentCount.incrementAndGet();
                        if (current % 1000 == 0) {
                            System.out.println(">>> Progress: " + current + " packets sent...");
                        }
                    } catch (Exception e) {
                        System.err.println("Packet failed: " + e.getMessage());
                    }
                });
            }

            executor.shutdown();
            if (executor.awaitTermination(5, TimeUnit.MINUTES)) {
                long endTime = System.currentTimeMillis();
                double duration = (endTime - startTime) / 1000.0;
                System.out.println("--- TEST COMPLETED ---");
                System.out.println("Total Packets: " + sentCount.get());
                System.out.println("Total Time: " + duration + " seconds");
                System.out.println("Avg Speed: " + String.format("%.2f", sentCount.get() / duration) + " packets/sec");
            }
        }
    }
}