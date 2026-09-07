package network.darkland.web;

import network.darkland.NexusApplication;
import network.darkland.cache.CacheMetrics;
import network.darkland.protocol.DataAddon;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CacheMetricsController {

    @GetMapping("/cache-metrics")
    public Map<String, Object> cacheMetrics() {
        java.util.Collection<DataAddon> addons = NexusApplication.getApplication()
                .getProtocolHandler()
                .getAllAddons();

        List<Map<String, Object>> regions = new ArrayList<>();

        long totalL1 = 0, totalL2 = 0, totalL3 = 0;

        for (DataAddon addon : addons) {
            String tag = addon.cacheKeyHeaderTag();
            CacheMetrics.Snapshot snap = CacheMetrics.get().snapshot(tag);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("region",       tag);
            row.put("addonId",      addon.addonId());
            row.put("addonName",    addon.addonName());
            row.put("l1Enabled",    addon.l1CacheEnabled());
            row.put("l1Hits",       snap.l1Hits());
            row.put("l2Hits",       snap.l2Hits());
            row.put("l3Hits",       snap.l3Hits());
            row.put("l1Ratio",      snap.l1Ratio());
            regions.add(row);

            totalL1 += snap.l1Hits();
            totalL2 += snap.l2Hits();
            totalL3 += snap.l3Hits();
        }

        long totalReads = totalL1 + totalL2 + totalL3;
        double overallRatio = totalReads == 0 ? 0.0 : (double) totalL1 / totalReads;

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("l1Hits", totalL1);
        overall.put("l2Hits", totalL2);
        overall.put("l3Hits", totalL3);
        overall.put("l1Ratio", overallRatio);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("regions", regions);
        out.put("overall", overall);
        out.put("timestamp", System.currentTimeMillis());
        return out;
    }
}
