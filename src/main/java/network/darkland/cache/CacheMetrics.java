package network.darkland.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class CacheMetrics {

    private static final CacheMetrics INSTANCE = new CacheMetrics();

    public static CacheMetrics get() {
        return INSTANCE;
    }

    private static final class RegionCounters {
        final LongAdder l1Hits = new LongAdder();
        final LongAdder l2Hits = new LongAdder();
        final LongAdder l3Hits = new LongAdder();
    }

    private final ConcurrentHashMap<String, RegionCounters> regions = new ConcurrentHashMap<>();

    private RegionCounters counters(String region) {
        return regions.computeIfAbsent(region, r -> new RegionCounters());
    }

    public void recordL1Hit(String region) {
        counters(region).l1Hits.increment();
    }

    public void recordL2Hit(String region) {
        counters(region).l2Hits.increment();
    }

    public void recordL3Hit(String region) {
        counters(region).l3Hits.increment();
    }

    public Snapshot snapshot(String region) {
        RegionCounters c = counters(region);
        return new Snapshot(c.l1Hits.sum(), c.l2Hits.sum(), c.l3Hits.sum());
    }

    public java.util.Map<String, Snapshot> snapshotAll() {
        java.util.Map<String, Snapshot> out = new java.util.HashMap<>();
        regions.forEach((region, c) -> out.put(region, new Snapshot(c.l1Hits.sum(), c.l2Hits.sum(), c.l3Hits.sum())));
        return out;
    }

    public record Snapshot(long l1Hits, long l2Hits, long l3Hits) {
        public long total() {
            return l1Hits + l2Hits + l3Hits;
        }

        public double l1Ratio() {
            long t = total();
            return t == 0 ? 0.0 : (double) l1Hits / t;
        }
    }
}
