package network.darkland.protocol;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RequestType {

    private static final ConcurrentHashMap<String, RequestType> REGISTRY = new ConcurrentHashMap<>();

    public static final RequestType GET_DATA       = of("GET_DATA");
    public static final RequestType SET_DATA       = of("SET_DATA");
    public static final RequestType UPDATE_DATA    = of("UPDATE_DATA");
    public static final RequestType REMOVE_DATA    = of("REMOVE_DATA");
    public static final RequestType BROADCAST      = of("BROADCAST");
    public static final RequestType LOAD_CACHE     = of("LOAD_CACHE");
    public static final RequestType INCREMENT_DATA = of("INCREMENT_DATA");
    public static final RequestType LIVE           = of("LIVE");
    public static final RequestType RANKING        = of("RANKING");
    public static final RequestType RANK_FINDER    = of("RANK_FINDER");

    private final String key;

    private RequestType(String key) {
        this.key = key;
    }
    public static RequestType of(String key) {
        Objects.requireNonNull(key, "key null olamaz");
        String normalized = key.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("RequestType key boş olamaz");
        }
        return REGISTRY.computeIfAbsent(normalized, RequestType::new);
    }

    public static Optional<RequestType> lookup(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(key.trim().toUpperCase()));
    }

    public String key() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestType)) return false;
        return key.equals(((RequestType) o).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}