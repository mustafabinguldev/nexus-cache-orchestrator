package network.darkland.web;

import network.darkland.NexusApplication;
import network.darkland.cache.CacheMetrics;
import network.darkland.cache.L1InvalidationBus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatsController {

    private static final long SSE_INTERVAL_MS = 5000L;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return getStatsMap();
    }

    @GetMapping(path = "/stream/stats", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamStats() {
        final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        final java.util.concurrent.ScheduledExecutorService exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-stats-sender");
            t.setDaemon(true);
            return t;
        });

        final Runnable sender = () -> {
            try {
                Map<String, Object> stats = getStatsMap();
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("stats").data(stats));
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        };

        exec.scheduleAtFixedRate(sender, 0, SSE_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        emitter.onCompletion(exec::shutdown);
        emitter.onTimeout(exec::shutdown);
        emitter.onError((ex) -> exec.shutdown());

        return emitter;
    }

    private Map<String, Object> getStatsMap() {
        NexusApplication nexus = NexusApplication.getApplication();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cachedEntries", nexus.getDataSize());
        stats.put("loadedAddons",  nexus.getAddonSize());
        stats.put("dirtyKeys",     nexus.getDataContainer().getDirtyKeys().size());

        long totalL1 = 0, totalL2 = 0, totalL3 = 0;
        for (CacheMetrics.Snapshot s : CacheMetrics.get().snapshotAll().values()) {
            totalL1 += s.l1Hits();
            totalL2 += s.l2Hits();
            totalL3 += s.l3Hits();
        }
        long totalReads = totalL1 + totalL2 + totalL3;
        double l1Ratio = totalReads == 0 ? 0.0 : (double) totalL1 / totalReads;

        stats.put("l1Hits",  totalL1);
        stats.put("l2Hits",  totalL2);
        stats.put("l3Hits",  totalL3);
        stats.put("l1HitRatio", l1Ratio);
        stats.put("clusterMode", L1InvalidationBus.isClusterModeEnabled());

        boolean dbOk;
        try {
            dbOk = nexus.getDataStore().verifyConnection();
        } catch (Exception e) {
            dbOk = false;
        }
        stats.put("mongoConnected", dbOk);
        stats.put("dbType", nexus.getDataStore().type().name());
        stats.put("timestamp", System.currentTimeMillis());

        return stats;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "time", System.currentTimeMillis());
    }
}