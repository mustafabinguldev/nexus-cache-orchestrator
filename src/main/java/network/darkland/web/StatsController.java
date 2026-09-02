package network.darkland.web;

import network.darkland.NexusApplication;
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

        boolean mongoOk;
        try {
            mongoOk = nexus.getMongoManager().verifyConnection();
        } catch (Exception e) {
            mongoOk = false;
        }
        stats.put("mongoConnected", mongoOk);
        stats.put("timestamp", System.currentTimeMillis());

        return stats;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "time", System.currentTimeMillis());
    }
}
