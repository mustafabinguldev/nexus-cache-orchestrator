package network.darkland.protocol;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtocolHandler {

    private final Map<Integer, DataAddon> registry = new ConcurrentHashMap<>();

    public void registerAddon(DataAddon addon) {
        DataAddon existing = registry.putIfAbsent(addon.addonId(), addon);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate addonId: " + addon.addonId()
                            + " (" + existing.addonName() + " ile " + addon.addonName() + " çakışıyor)"
            );
        }
    }

    public Optional<DataAddon> getAddonById(int protocolId) {
        return Optional.ofNullable(registry.get(protocolId));
    }

    public int getAddonSize() {
        return registry.size();
    }

    public List<String> getAddondsNames() {
        return registry.values().stream()
                .map(addon -> addon.getClass().getSimpleName())
                .toList();
    }

    public Collection<DataAddon> getAllAddons() {
        return registry.values();
    }
}