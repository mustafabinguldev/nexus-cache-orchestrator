package network.darkland.model.schema;

import network.darkland.protocol.RequestType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The JSON counterpart of a Java addon's {@code handleRequest} body: which request
 * types a model accepts, and optionally which sources may send them.
 *
 * <pre>
 * "access": {
 *   "default": "allow",
 *   "deny":    ["REMOVE_DATA"],
 *   "sources": { "REMOVE_DATA": ["admin"], "*": ["lobby", "survival"] }
 * }
 * </pre>
 */
public final class AccessRules {

    private static final String ANY_TYPE = "*";

    private final boolean allowByDefault;
    private final Set<String> allow;
    private final Set<String> deny;
    private final Map<String, Set<String>> sources;

    AccessRules(boolean allowByDefault, Set<String> allow, Set<String> deny, Map<String, Set<String>> sources) {
        this.allowByDefault = allowByDefault;
        this.allow          = Set.copyOf(allow);
        this.deny           = Set.copyOf(deny);
        this.sources        = Map.copyOf(sources);
    }

    public static AccessRules allowAll() {
        return new AccessRules(true, Set.of(), Set.of(), Map.of());
    }

    public boolean permits(String source, RequestType type) {
        String key = type.key();

        if (deny.contains(key)) return false;
        if (!allowByDefault && !allow.contains(key)) return false;

        return sourceAllowed(source, sources.get(key)) && sourceAllowed(source, sources.get(ANY_TYPE));
    }

    private boolean sourceAllowed(String source, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) return true;
        if (allowed.contains(ANY_TYPE)) return true;
        return source != null && allowed.contains(source.toLowerCase(Locale.ROOT));
    }

    public boolean allowByDefault() {
        return allowByDefault;
    }

    public Set<String> allow() {
        return allow;
    }

    public Set<String> deny() {
        return deny;
    }

    public Map<String, Set<String>> sources() {
        return Collections.unmodifiableMap(sources);
    }
}
