package network.darkland.web;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AddonsController {

    @GetMapping("/addons")
    public List<Map<String, Object>> addons() {
        return NexusApplication.getApplication()
                .getProtocolHandler()
                .getAllAddons()
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    @GetMapping(path = "/addons/csv", produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> addonsCsv() {
        List<Map<String, Object>> list = addons();
        StringBuilder sb = new StringBuilder();
        sb.append("id,name,className,database,collection,cacheTTL\n");
        for (Map<String, Object> a : list) {
            sb.append(escapeCsv(String.valueOf(a.get("id")))).append(',')
              .append(escapeCsv(String.valueOf(a.get("name")))).append(',')
              .append(escapeCsv(String.valueOf(a.get("className")))).append(',')
              .append(escapeCsv(String.valueOf(a.get("database")))).append(',')
              .append(escapeCsv(String.valueOf(a.get("collection")))).append(',')
              .append(String.valueOf(a.get("cacheTTL"))).append('\n');
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=addons.csv")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(sb.toString());
    }

    private Map<String, Object> toMap(DataAddon addon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         addon.addonId());
        m.put("name",       addon.addonName());
        m.put("className",  addon.getClass().getSimpleName());
        m.put("database",   addon.getNamespace());
        m.put("collection", addon.getDataset());
        m.put("cacheTTL",   addon.getCacheTTL());
        return m;
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\n") || s.contains("\"")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
