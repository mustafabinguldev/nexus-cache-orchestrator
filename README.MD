# Nexus Core

**Shared data, caching, and messaging for distributed Minecraft servers.**

Nexus Core centralizes player and game data behind Redis messaging. Server plugins send requests; Core handles caching, MongoDB persistence, and responses through a common addon API.

[Quick start](#quick-start) · [Addons](#create-an-addon) · [Protocol](#messaging) · [Tests](diagnostics/README.md) · [Roadmap](ROADMAP.md)

## What it provides

- **Multi-level caching:** optional Caffeine L1, Redis L2, and MongoDB storage.
- **Recoverable requests:** Redis Streams with acknowledgements and pending-message redelivery.
- **Data addons:** define schemas, validation, cache prefixes, and TTLs in Java.
- **Signed messages:** HMAC-SHA256, timestamp checks, and nonce validation.
- **Web dashboard:** authenticated access to statistics, addons, and cached values.
- **Optional metrics:** InfluxDB integration and cache hit statistics.

```text
Minecraft servers → Redis Stream → Nexus Core → MongoDB
                                      ↕
                                 L1 / Redis cache
Minecraft servers ← Redis Pub/Sub ← Nexus Core
```

## Quick start

You need **Java 21+**, **Maven 3.9+**, **Redis 7+**, and **MongoDB**. Start Redis and MongoDB before launching Core.

```bash
git clone https://github.com/mustafabinguldev/nexus-cache-orchestrator.git
cd nexus-cache-orchestrator
mvn verify
```

Set `NEXUS_SIGNING_KEY` to the **same secret on Core and its clients**:

```bash
# Bash — replace the placeholder with your own secret
export NEXUS_SIGNING_KEY="<your-shared-secret>"
```

```powershell
# PowerShell
$env:NEXUS_SIGNING_KEY = "<your-shared-secret>"
```

Run the executable JAR built from the current `1.6.5` project:

```bash
java -jar target/nexus-cache-orchestrato-1.6.5-boot.jar
```

On first launch, the **terminal setup wizard** asks for database connections, optional metrics, a web port, and admin credentials. It saves `config.json` in the working directory, with a BCrypt hash for the admin password.

Open **http://localhost:8088**, or the web port you selected, and sign in.

For the container workflow, see the [Docker installation guide](docker-installation.md). Review its exposed ports and credentials before deployment.

## Configuration

Connection and dashboard settings live in `config.json`; signing and cluster options use environment variables.

| Setting | Default | Purpose |
| --- | --- | --- |
| `redisHost` / `redisPort` | `127.0.0.1` / `6379` | Redis connection |
| `redisUser` / `redisPass` | Empty | Redis authentication |
| `mongoUri` | `mongodb://localhost:27017` | MongoDB connection |
| `webPort` | `8088` | Dashboard port |
| `metricsEnabled` | `false` | Enable InfluxDB metrics |
| `NEXUS_SIGNING_KEY` | Unset | Shared HMAC secret; incoming messages are rejected without it by default |
| `NEXUS_ALLOW_UNSIGNED_MESSAGES` | `false` | Explicit unsigned mode for isolated development or migration |
| `NEXUS_CLUSTER_MODE` | `false` | Enable cross-instance L1 invalidation |

Keep `config.json`, secrets, and database credentials out of version control. Cluster invalidation does **not** provide distributed atomic increments or exactly-once processing.

## Create an addon

Extend `DataAddon`, annotate persisted fields, and choose a unique protocol ID and cache prefix. Use a string ID, such as a player's UUID.

```java
import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.RequestType;
import network.darkland.protocol.backup.annotations.DbDataModels;

public class PlayerStatsAddon extends DataAddon {
    @DbDataModels(isId = true)
    private String uuid;

    @DbDataModels(isId = false, defaultValue = "0")
    private long kills;

    public int addonId() { return 100; }
    public String addonName() { return "Player Stats"; }
    public String getDatabase() { return "nexus_core_db"; }
    public String getCollection() { return "player_stats"; }
    public String cacheKeyHeaderTag() { return "player_stats"; }
    public int getCacheTTL() { return 300; }

    public boolean handleRequest(String source, RequestType type,
                                 NexusJsonDataContainer data) {
        // Apply your server permissions and payload validation here.
        return !type.equals(RequestType.REMOVE_DATA) || "admin".equals(source);
    }
}
```

Compile against the Core library JAR, place your addon JAR in **`addons/`**, and restart Core. Addons are discovered at startup and must have a public no-argument constructor.

Cache keys use `{prefix}_{id}`, for example `player_stats_<uuid>`. Returning `false` from `handleRequest` rejects the request. Override `l1CacheEnabled()` to disable L1 for an addon.

For custom asynchronous handlers, use `RedisManager.processTask` or `processMongoTask`, wait for external futures inside the submitted task, and propagate failures so Core can track completion.

## Messaging

| Direction | Transport | Destination |
| --- | --- | --- |
| Client → Core | Redis Stream | `darkland_nexus_stream`, field `payload` |
| Core → Client | Redis Pub/Sub | `darkland_nexus_<source>` |

Each request carries `protocol`, `source`, `type`, and `data`, plus `timestamp` (epoch milliseconds), a unique `nonce`, and `sig`. Use [MessageAuth.stamp](src/main/java/network/darkland/redis/MessageAuth.java) to stamp Java-generated packets before sending them with `XADD`. Initial requests must be within the five-minute timestamp window.

| Request | Behavior |
| --- | --- |
| `GET_DATA` | Read a model; create defaults when the record is absent |
| `SET_DATA` | Create or replace model data using the addon schema |
| `INCREMENT_DATA` | Increase a numeric field using `key`, `field`, and `amount` |
| `REMOVE_DATA` | Remove cached data; `all: true` also deletes the MongoDB record |
| `RANKING` | Return records ordered by a field |
| `RANK_FINDER` | Return a record's position in a ranking |
| `LOAD_CACHE` | Load a record into cache using top-level `protocol` and `key` |

**Delivery is at least once.** ACK waits for tracked tasks and their pending MongoDB writes. Pending write snapshots remain independent of L1 expiry or eviction. A process crash between a side effect and its completion receipt can still replay a mutation; design custom side effects accordingly. Pub/Sub responses are not durable.

Redis `setData` and `deleteData` complete before returning and propagate write failures. Detached addon threads cannot be tracked automatically; direct mutations outside a stream request use background persistence.

## Testing

The [regression guide](diagnostics/README.md) includes setup instructions and a PowerShell runner for dedicated local Redis/MongoDB containers.

Latest recorded validation: **27 data/protocol checks, 8 HTTP checks, and 2 signature-policy checks passed**, alongside Maven packaging. These diagnostics run separately from Maven's test phase. Multi-node concurrency, crash recovery guarantees, and long-running load tests are outside this validation scope.

## License

[MIT](LICENSE) — Mustafa Bingül.
