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

    @GetMapping("/stats")
    public Map<String, Object> stats() {
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