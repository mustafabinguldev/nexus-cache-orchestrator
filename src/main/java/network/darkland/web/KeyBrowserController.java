package network.darkland.web;

import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import network.darkland.protocol.DataAddon;
import network.darkland.redis.RedisManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class KeyBrowserController {

    private static final Logger LOGGER = Logger.getLogger(KeyBrowserController.class.getName());
    private static final int MAX_PAGE_SIZE = 200;

    @GetMapping("/addons/{addonId}/keys")
    public Map<String, Object> listKeys(@PathVariable("addonId") int addonId,
                                        @RequestParam(name = "cursor", defaultValue = "0") String cursor,
                                        @RequestParam(name = "limit", defaultValue = "50") int limit) {
        try {
            NexusApplication app = NexusApplication.getApplication();

            Optional<DataAddon> addonOpt = app.getProtocolHandler().getAllAddons().stream()
                    .filter(a -> a.addonId() == addonId)
                    .findFirst();

            if (addonOpt.isEmpty()) {
                return Map.of("error", "addon_not_found", "addonId", addonId);
            }

            DataAddon addon = addonOpt.get();
            String pattern = addon.cacheKeyHeaderTag() + "_*";
            int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

            RedisManager.ScanKeysResult scan = app.getRedisManager().scanKeys(pattern, cursor, safeLimit);

            List<Map<String, Object>> rows = scan.keys().stream()
                    .map(key -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("key",   key);
                        row.put("inL1",  app.getDataContainer().getDataModelFromKey(key).isPresent());
                        row.put("ttl",   app.getRedisManager().ttl(key));
                        return row;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("addonId",    addon.addonId());
            out.put("addonName",  addon.addonName());
            out.put("keys",       rows);
            out.put("nextCursor", scan.cursor());
            out.put("done",       "0".equals(scan.cursor()));
            return out;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[KeyBrowser] /addons/" + addonId + "/keys error:", e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error",       "internal_error");
            err.put("message",     e.getMessage());
            err.put("exception",   e.getClass().getName());
            return err;
        }
    }

    @GetMapping("/keys/value")
    public Map<String, Object> keyValue(@RequestParam("key") String key) {
        try {
            NexusApplication app = NexusApplication.getApplication();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);

            Optional<DataModel> l1 = app.getDataContainer().getDataModelFromKey(key);
            if (l1.isPresent()) {
                out.put("source", "L1");
                out.put("value",  l1.get().getValueJson());
                out.put("ttl",    app.getRedisManager().ttl(key));
                return out;
            }

            Optional<String> l2 = app.getRedisManager().getData(key);
            if (l2.isPresent()) {
                out.put("source", "L2");
                out.put("value",  l2.get());
                out.put("ttl",    app.getRedisManager().ttl(key));
                return out;
            }

            out.put("source", "MISS");
            out.put("value",  null);
            out.put("ttl",    -2);
            return out;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[KeyBrowser] /keys/value error, key=" + key, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error",     "internal_error");
            err.put("message",   e.getMessage());
            err.put("exception", e.getClass().getName());
            return err;
        }
    }
}