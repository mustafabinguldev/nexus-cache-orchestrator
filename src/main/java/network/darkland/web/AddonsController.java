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

    private Map<String, Object> toMap(DataAddon addon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         addon.addonId());
        m.put("name",       addon.addonName());
        m.put("className",  addon.getClass().getSimpleName());
        m.put("database",   addon.getDatabase());
        m.put("collection", addon.getCollection());
        m.put("cacheTTL",   addon.getCacheTTL());
        return m;
    }
}
