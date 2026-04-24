package network.darkland.protocol;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolHandler {

    private ConcurrentHashMap<Integer, DataAddon> addons;

    public ProtocolHandler() {
        addons = new ConcurrentHashMap<>();
    }


    public Optional<DataAddon> getAddonById(int id) {
        DataAddon addon = addons.get(id);
        if (addon != null) {
            return Optional.of(addon);
        }
        return Optional.empty();
    }

    public void registerAddon(DataAddon addon) {
        if (addons.keySet().stream().anyMatch(integer -> integer == addon.addonId())) {
            return;
        }

        addons.put(addon.addonId(), addon);
    }

    public List<String> getAddondsNames() {
        return addons.values().stream()
                .map(addon -> addon.getClass().getSimpleName())
                .toList();
    }


}
