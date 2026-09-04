package network.darkland.redis.security;

public final class NexusSecurityConfig {


    public static final String SHARED_SECRET =
            System.getenv().getOrDefault("NEXUS_SIGNING_KEY", "");

    public static final long TIMESTAMP_WINDOW_MILLIS = 5 * 60 * 1000L;

    public static boolean isSigningEnabled() {
        return !SHARED_SECRET.isBlank();
    }

    private NexusSecurityConfig() {
    }
}