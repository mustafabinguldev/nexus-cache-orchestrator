# ⬡ Nexus Core — Centralized Data Orchestration Layer for Distributed Minecraft Servers

> *"The central brain between your distributed Minecraft infrastructure and persistent data layer."*

[Java](https://www.java.com/) ([image](https://img.shields.io/badge/Java-17+-blue?style=flat-square&logo=java))
[Redis](https://redis.io/) ([image](https://img.shields.io/badge/Redis-Message%20Broker-red?style=flat-square&logo=redis))
[MongoDB](https://www.mongodb.com/) ([image](https://img.shields.io/badge/MongoDB-Persistence-green?style=flat-square&logo=mongodb))
[Security](#-security-layer-new) ([image](https://img.shields.io/badge/Security-HMAC%20%2B%20Anti--Replay-orange?style=flat-square))

---

## 📖 Table of Contents

* [What is Nexus Core?](#-what-is-nexus-core)
* [System Architecture](#️-system-architecture)
* [Data Flow](#-data-flow)
* [🔐 Security Layer (New)](#-security-layer-new)
* [Core Components](#-core-components)
* [Version History / Changelog](#-version-history--changelog)
* [Getting Started](#-getting-started)
* [Writing an Addon](#️-writing-an-addon)
* [DataAddon API Reference](#-dataaddon-api-reference)
* [Annotation Reference](#️-annotation-reference)
* [Request Lifecycle](#-request-lifecycle)
* [Protocol & Packet Structure](#-protocol--packet-structure)
* [Redis Key Strategy](#️-redis-key-strategy)
* [Configuration](#️-configuration)
* [Best Practices](#-best-practices)
* [License](#️-license)

---

## 🧠 What is Nexus Core?

**Nexus Core** is a standalone Java application that acts as a **centralized orchestration layer** for a distributed Minecraft network. Instead of every Spigot server connecting directly to MongoDB and managing its own cache, all data operations are delegated to Nexus Core through a **Redis pub/sub messaging layer**.

This design solves three critical problems encountered at scale:

| Problem               | Without Nexus Core                             | With Nexus Core                                  |
| --------------------- | ---------------------------------------------- | ------------------------------------------------ |
| **DB Connections**    | Every server maintains its own connection pool | A single, optimized connection pool              |
| **Cache Consistency** | Every server has its own stale cache           | Centralized Redis cache, always consistent       |
| **Data Logic**        | Repeated across every server's codebase        | Defined once in a `DataAddon`, shared everywhere |

In short, Nexus Core can be thought of as a **microservice** built specifically for Minecraft infrastructure — Nexus Core owns all data, while your Spigot servers act only as thin clients.

---

## 🏗️ System Architecture

```text
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐     │
│   │  Spigot #1   │    │  Spigot #2   │    │  Spigot #3   │     │
│   │  (Lobby)     │    │  (PvP)       │    │  (Survival)  │     │
│   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘     │
│          │                   │                   │             │
│          └───────────────────┼───────────────────┘             │
│                              │ Redis Pub/Sub (signed packets)  │
│                    ┌─────────▼──────────┐                      │
│                    │    Redis Broker     │                      │
│                    │   (Message Layer)   │                      │
│                    └─────────┬──────────┘                      │
│                              │                                  │
│                    ┌─────────▼──────────┐                      │
│                    │   ⬡ NEXUS CORE ⬡   │  ← YOU ARE HERE     │
│                    │                    │                      │
│                    │  ┌──────────────┐  │                      │
│                    │  │   Security   │  │  HMAC / Nonce /      │
│                    │  │ Verification │  │  Timestamp checks    │
│                    │  └──────┬───────┘  │                      │
│                    │  ┌──────▼───────┐  │                      │
│                    │  │ AddonRegistry│  │                      │
│                    │  │  Addon #100  │  │                      │
│                    │  │  Addon #200  │  │                      │
│                    │  │  Addon #500  │  │                      │
│                    │  └──────┬───────┘  │                      │
│                    └─────────┼──────────┘                      │
│                              │ CRUD Operations                  │
│                    ┌─────────▼──────────┐                      │
│                    │      MongoDB        │                      │
│                    │  ┌───────────────┐ │                      │
│                    │  │ nexus_core_db │ │                      │
│                    │  │  → players    │ │                      │
│                    │  │  → guilds     │ │                      │
│                    │  │  → economy    │ │                      │
│                    │  └───────────────┘ │                      │
│                    └────────────────────┘                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Data Flow

Every interaction follows a strict **request → validation → orchestration → response** lifecycle:

```text
Spigot Server              Redis                 Nexus Core              MongoDB
      │                       │                        │                     │
      │  1. PUBLISH packet     │                        │                     │
      │  { protocol, source,   │                        │                     │
      │    type, data,         │                        │                     │
      │    sig, nonce,         │                        │                     │
      │    timestamp }         │                        │                     │
      │───────────────────────►│                        │                     │
      │                        │  2. Route to listener  │                     │
      │                        │───────────────────────►│                     │
      │                        │                        │ 3. Security chain:  │
      │                        │                        │   a) HMAC signature │
      │                        │                        │   b) Timestamp range│
      │                        │                        │   c) Nonce replay   │
      │                        │                        │      check          │
      │                        │                        │   (REJECT if any    │
      │                        │                        │    check fails)     │
      │                        │                        │                     │
      │                        │                        │ 4. Find protocol in │
      │                        │                        │    AddonRegistry    │
      │                        │                        │ 5. handleRequest()  │
      │                        │                        │    pre-validation   │
      │                        │                        │ 6. Check Redis cache│
      │                        │                        │──── CACHE HIT? ────►│
      │                        │                        │◄── Return from cache│
      │                        │                        │ (CACHE MISS)         │
      │                        │                        │ 7. MongoDB query    │
      │                        │                        │────────────────────►│
      │                        │                        │◄── Document ────────│
      │                        │                        │ 8. Write to cache   │
      │                        │  9. PUBLISH response   │                     │
      │                        │◄───────────────────────│                     │
      │  10. Receive response  │                        │                     │
      │◄───────────────────────│                        │                     │
```

### Request Types

| Type             | Description                                       | MongoDB Operation                | Cache         |
| ---------------- | ------------------------------------------------- | -------------------------------- | ------------- |
| `GET_DATA`       | Retrieves a document by its ID field              | `findOne()`                      | Read-through  |
| `SET_DATA`       | Inserts or updates a document                     | `replaceOne(upsert)`             | Write-through |
| `REMOVE_DATA`    | Deletes a document by its ID                      | `deleteOne()`                    | Invalidate    |
| `INCREMENT_DATA` | Atomically increments an existing numeric field   | `findOne()` + in-memory mutation | Write-through |
| `UPDATE_DATA`    | Partially updates fields in an existing document  | `updateOne()`                    | Write-through |
| `RANKING`        | Retrieves a global Top-N leaderboard (ASC/DESC)   | Sorted query                     | Bypass        |
| `RANK_FINDER`    | Calculates a key's position in the global ranking | Optimized query                  | Bypass        |
| `BROADCAST`      | Sends data to a target server via Redis pub/sub   | None                             | Bypass        |
| `LOAD_CACHE`     | Preloads a document into memory by ID             | `findOne()`                      | Warm          |

---

## 🔐 Security Layer (New)

A new security layer providing **message integrity and replay attack protection** has recently been added to the project. Every packet received through Redis now passes through a three-stage validation chain before being processed: **signature → timestamp → nonce**.

### 1) HMAC-SHA256 Signature Verification

* Every packet is signed by the sender using **HmacSHA256** with a shared secret key (`NEXUS_SIGNING_KEY`), and the signature is stored in the `sig` field as Base64.
* Nexus Core recalculates the expected signature from the remaining packet contents, excluding the `sig` field, and compares it against the received signature.
* If the signatures do not match, the packet is **rejected immediately** and the event is logged at warning level.
* If the `NEXUS_SIGNING_KEY` environment variable is not defined, the system **disables signature verification** and logs a warning only once. This behavior is acceptable only during migration/development; **the key must always be configured in production.**

### 2) Timestamp Validation

* Every packet must contain a `timestamp` field.
* Nexus Core calculates the difference between the packet timestamp and the server clock; if the difference exceeds the allowed **5-minute** window, the packet is rejected.
* This mechanism prevents old or intercepted packets from being replayed long after they were originally sent.

### 3) Nonce-Based Replay Protection

* Every packet contains a single-use `nonce` field.
* Each used nonce is stored together with its timestamp in an in-memory concurrent map (`ConcurrentHashMap`).
* If the same nonce is encountered a second time, the packet is identified as a **replay attack** and rejected.
* Expired nonce entries are automatically cleaned up according to the timestamp window, preventing unlimited memory growth.

### Validation Order

An incoming packet is checked in the following order — if any step fails, the packet is silently dropped (logged but not processed):

```text
1. Does the sig       field exist, and does HMAC verification pass?
2. Does the timestamp field exist, and is it within the allowed window?
3. Does the nonce     field exist, and has it not been used before?
4. Is the type        field a valid RequestType?
         └──► If all checks pass, normal processing continues.
```

### How to Enable It

The packet senders (Spigot servers) and Nexus Core must use the **same shared secret key**:

```bash
export NEXUS_SIGNING_KEY="a-long-and-unpredictable-secret-key"
```

> ⚠️ **Important:** This key must be identical across all components in the network and must never be committed to version control (Git). Regular key rotation is recommended.

## 📜 Version History / Changelog

A detailed change history compiled from the project's published GitHub releases:

### 🔐 Security Update - Nexus Engine v1.5

* Added HMAC-SHA256-based packet signing and verification (`NexusReceiver`)
* Added timestamp-based validation windows to reject outdated packets
* Added nonce-based replay attack protection with automatic nonce cleanup
* Disabled verification with a one-time warning for backward compatibility when `NEXUS_SIGNING_KEY` is not defined

### v1.4.2 — Nexus Engine (June 3)

* Made the `NexusApplication` singleton instance `volatile` to prevent stale references in multi-threaded environments
* Wrapped `URLClassLoader` with `try-with-resources` to release file handles immediately after JAR loading
* Resolved a parallel MongoDB connection race condition by adding a 10-second startup delay to the scheduled task
* `getInfluxDBManager()` now returns `Optional<InfluxDBManager>`
* Fixed a telemetry bug in `handleIncrementData` where the current payload was replaced with an outdated payload
* Improved performance by caching `getApplication()` / `getRedisManager()` calls in local variables
* Added early input validation before Redis tasks are started

### v1.4.1 — Nexus Core (May 23)

* Added connection timeout handling for the MongoDB client
* Enabled periodic MongoDB health checks through a watchdog system
* Added automatic detection of connection failure states
* Added a fail-safe shutdown mechanism for critical database failures
* Added Swing-based user notifications for critical runtime errors
* **Behavior change:** The application now shuts down gracefully if MongoDB becomes unavailable

### v1.4.0 — Nexus Core (April 29)

* Redis and MongoDB connection information is now stored in a persistent configuration file ("Zero-Touch" startup)
* **Global Ranking Protocol:** Asynchronous Top-N leaderboard retrieval across all servers with dynamic ASC/DESC sorting support
* **Rank Finder Protocol:** Calculates a key's position in the global ranking using optimized MongoDB queries
* Added `.remove(String key)` to `NexusJsonDataContainer` (for example, to remove sensitive fields such as `_id` before serialization)
* Resolved Jackson compatibility issues by moving to native Java structures
* Improved `CompletableFuture` usage to prevent heavy ranking/position queries from blocking the main communication thread

### v1.3.0 — Synchronization Update (April 27)

* Added real-time data flow and inter-instance communication protocols
* Added dynamic cache TTL support, allowing each `DataAddon` to define its own `getCacheTTL()` value
* Added L1/L2 cache auto-synchronization through Redis Keyspace Notifications (`notify-keyspace-events Ex`) — when a Redis key expires, the local RAM cache is automatically cleared
* Added sliding TTL using "touch-to-renew" logic: active data remains cached while inactive data is automatically evicted when it expires
* Improved inbound/outbound worker threads for non-blocking operation under heavy load
* Reduced serialization overhead by caching reflection-based field/annotation scanning inside `DataAddon`

### v1.2.0 — DataAddon Performance Revision (April 26)

* Added the `INCREMENT_DATA` protocol: atomic numeric field increments without requiring a complete `SET_DATA` round trip (supports `int`/`long` and `double`)
* `ObjectMapper` is now a shared static final constant across all `DataAddon` instances, reducing GC pressure
* Field scanning (`getDeclaredFields()`) is performed once and cached; repeated calls are nearly free through double-checked locking
* Stopped unnecessary `modelInitComp()` execution for models read from the L1 in-memory cache
* **Bug fixes:** Eliminated a side effect in `modelInit()` that caused silent data corruption under concurrent load; added key-based locking to prevent concurrent increment operations on the same key from overwriting each other; fixed a null-unboxing NPE in `handleRemove()` using `Boolean.TRUE.equals()`
* Migrated all logging to `java.util.logging` and added structured `[DataAddon/]` prefixes

### v1.1.0 — Cache Layer Revision (April 25)

* Completely redesigned the `RedisDataContainer` data synchronization layer
* **Redis-Master Cache Hierarchy:** Redis is now the single source of truth, with the strict priority order `Redis → L1 Cache → MongoDB`
* Keys evicted or expired from Redis are automatically restored from L1, marked as "dirty," and written back to MongoDB
* The reconciliation task now processes records in batches of 50 instead of querying all records simultaneously and causing sudden MongoDB load spikes
* Added an O(1) reverse index (`id → key`) for `getDataModelFromId()` instead of O(n) linear scanning
* **Bug fixes:** Resolved data loss during flush failures, a TOCTOU race condition in `removeModel()`, a potential deadlock during reconciliation, and an issue where `addModelFix()` left Redis outdated

### v1.0.0 — First Stable Release (April 24)

* Implemented the core functionality and released the first production-ready stable build

---

## 🚀 Getting Started

### Prerequisites

* Java 17+
* A running Redis instance (`redis-server`)
* A running MongoDB instance (`mongod`)

### Build & Run

```bash
# Clone the repository
git clone https://github.com/mustafabinguldev/nexus-cache-orchestrator.git
cd nexus-cache-orchestrator

# Define the shared signing key (recommended)
export NEXUS_SIGNING_KEY="a-long-and-unpredictable-secret-key"

# Build with Maven
mvn clean package -DskipTests

# Run the application
java -jar target/nexus-core-4.0.jar
```

The **Nexus Core Dashboard** will open. Enter the Redis host, MongoDB URI, and server port, then click **INITIALIZE NEXUS ENGINE**.

### Default Configuration

| Field       | Default                                                          |
| ----------- | ---------------------------------------------------------------- |
| Redis Host  | `127.0.0.1`                                                      |
| MongoDB URI | `mongodb://localhost:27017`                                      |
| Server Port | `8080`                                                           |
| Signing Key | *(verification disabled if undefined — configure in production)* |

---

## 🛠️ Writing an Addon

An addon is the fundamental data unit in Nexus Core. Each addon represents a single document collection, defines its schema using annotations, and can optionally intercept requests before they are processed.

### Step 1 — Extend `DataAddon`

```java
package network.darkland.addons;

import network.darkland.protocol.DataAddon;
import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.protocol.backup.annotations.DbDataModels;

public class PlayerStatsAddon extends DataAddon {

    @Override
    public int addonId() {
        return 100; // Must be globally unique across all addons
    }

    @Override
    public String addonName() {
        return "Player Stats";
    }

    @Override
    public String getDatabase() {
        return "nexus_core_db";
    }

    @Override
    public String getCollection() {
        return "player_stats"; // db.player_stats
    }

    @Override
    public String cacheKeyHeaderTag() {
        return "stats"; // Redis key: "stats:<value of the @isId field>"
    }

    @DbDataModels(isId = true)
    private String uuid; // Primary key — used for both DB queries and cache keys

    @DbDataModels(defaultValue = "0", isId = false)
    private int kills;

    @DbDataModels(defaultValue = "0", isId = false)
    private int deaths;

    @DbDataModels(defaultValue = "0", isId = false)
    private double balance;

    @DbDataModels(defaultValue = "false", isId = false)
    private boolean isPremium;

    @Override
    public boolean handleRequest(String source, RequestType requestType, NexusJsonDataContainer data) {
        // 'source' is the ID of the server that sent the packet, e.g. "pvp-1"
        // Returning false rejects the request before processing.

        if (requestType == RequestType.REMOVE_DATA) {
            // Example: only allow delete operations from the "admin" server
            return source.equals("admin");
        }

        return true;
    }
}
```

### Step 2 — Register the Addon

```java
// NexusApplication.java
NexusApplication.getInstance().getProtocolHandler().registerAddon(new PlayerStatsAddon());
```

That's it. Nexus Core will now automatically process all `GET_DATA`, `SET_DATA`, and `REMOVE_DATA` requests for protocol ID `100` and route them to the `player_stats` MongoDB collection.

---

## 📚 DataAddon API Reference

```java
public abstract class DataAddon {

    /** Unique numeric ID for this addon. Remote clients send it as the `protocol` field in their packets. */
    public abstract int addonId();

    /** Human-readable name displayed in the dashboard and logs. */
    public abstract String addonName();

    /** Name of the MongoDB database used by this addon. */
    public abstract String getDatabase();

    /** MongoDB collection read from and written to by this addon. */
    public abstract String getCollection();

    /**
     * Prefix used by Redis cache keys managed by this addon.
     * Full key format: "{cacheKeyHeaderTag}:{valueOfIdField}"
     */
    public abstract String cacheKeyHeaderTag();

    /**
     * Called before every data operation (AFTER the security validation chain).
     *
     * @param source      ID of the server initiating the request
     * @param requestType GET_DATA / SET_DATA / REMOVE_DATA / INCREMENT_DATA / ...
     * @param data        Payload container from the incoming packet
     * @return true  → process the request normally
     *         false → reject it and send an empty response to the source
     */
    public abstract boolean handleRequest(
        String source,
        RequestType requestType,
        NexusJsonDataContainer data
    );
}
```

---

## 🏷️ Annotation Reference

The `@DbDataModels` annotation allows Nexus Core to discover your schema at runtime through reflection. Every field that should be persisted must be annotated.

```java
@DbDataModels(isId = true)
private String uuid;
```

| Parameter      | Type      | Required                  | Description                                                                                          |
| -------------- | --------- | ------------------------- | ---------------------------------------------------------------------------------------------------- |
| `isId`         | `boolean` | ✅ Yes                     | Marks this field as the primary key. Every addon must have **exactly one** field with `isId = true`. |
| `defaultValue` | `String`  | ✅ Yes (if `isId = false`) | The value written to MongoDB when the field is missing from the incoming packet.                     |

### Supported Field Types

| Java Type             | Example `defaultValue` |
| --------------------- | ---------------------- |
| `String`              | `"unknown"`            |
| `int` / `Integer`     | `"0"`                  |
| `long` / `Long`       | `"0"`                  |
| `double` / `Double`   | `"0.0"`                |
| `boolean` / `Boolean` | `"false"`              |

---

## 🔄 Request Lifecycle

```text
Incoming Redis Packet
        │
        ▼
┌─────────────────────┐
│  Security Chain      │      ┌──────────────┐
│  sig → timestamp →  │─────►│ Failed?      │──► Log and drop packet
│  nonce              │      └──────────────┘
└────────┬────────────┘
         │ valid
         ▼
┌───────────────────┐
│  Parse JSON       │
│  Extract protocol │
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│  AddonRegistry    │      ┌─────────────┐
│  lookup(protocol) │─────►│ Not found?  │──► Log WARN and drop packet
└────────┬──────────┘      └─────────────┘
         │
         ▼
┌───────────────────┐
│  handleRequest()  │      ┌─────────────┐
│  (your code)      │─────►│ Returned    │──► Send an empty response
└────────┬──────────┘      │ false?      │    to the source
         │ true            └─────────────┘
         ▼
┌───────────────────┐
│  Route according  │
│  to RequestType   │
└────────┬──────────┘
         │
     ┌───┴────┬────────────┬──────────
     ▼        ▼            ▼
  GET_DATA SET_DATA   REMOVE_DATA
     │        │            │
     ▼        ▼            ▼
  Check    Write to     Delete from
  Redis    Mongo +      Mongo +
  cache    update       invalidate
     │     cache        cache
     ▼
  Cache hit?
     │ No
     ▼
  Query MongoDB
  Write to cache
     │
     ▼
  Serialize response
  Publish to Redis (source channel)
```

---

## 📦 Protocol & Packet Structure

### Incoming Packet (Spigot → Redis → Nexus)

When the security layer is enabled, every packet must contain the `sig`, `timestamp`, and `nonce` fields:

```json
{
  "protocol": 100,
  "source": "pvp-1",
  "type": "GET_DATA",
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000"
  },
  "timestamp": 1719900000000,
  "nonce": "b7f2b1b0-4b3b-4e29-9e2b-6b8f2b1b0b7f",
  "sig": "base64-hmac-sha256-signature"
}
```

### Outgoing Packet (Nexus → Redis → Spigot)

```json
{
  "protocol": 100,
  "source": "nexus",
  "type": "BROADCAST",
  "target": "pvp-1",
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "kills": 142,
    "deaths": 38,
    "balance": 2500.75,
    "isPremium": true
  }
}
```

---

## 🗝️ Redis Key Strategy

```text
{cacheKeyHeaderTag}_{valueOfIdField}
```

| Addon              | `cacheKeyHeaderTag` | ID Field Value | Full Redis Key    |
| ------------------ | ------------------- | -------------- | ----------------- |
| `PlayerStatsAddon` | `stats`             | `Steve`        | `stats_Steve`     |
| `GuildAddon`       | `guild`             | `Darkland`     | `guild_Darkland`  |
| `EconomyAddon`     | `eco`               | `550e8400...`  | `eco_550e8400...` |

> ⚠️ **Warning:** Two addons using the same `cacheKeyHeaderTag` can overwrite each other's cache entries. Always use unique prefixes.

Cache entries are stored as serialized JSON strings and are automatically invalidated during `SET`/`DELETE` operations.

---
## ⚙️ Configuration

Configuration is entered through the startup interface. There is intentionally no required config file — connection parameters can be entered live and validated during bootstrapping (and can also be persisted since v1.4.0).

| Field               | Example                          | Notes                                                                     |
| ------------------- | -------------------------------- | ------------------------------------------------------------------------- |
| `REDIS HOST`        | `127.0.0.1`                      | Host/IP address of your Redis server. Port defaults to `6379`.            |
| `MONGO URI`         | `mongodb://user:pass@host:27017` | Full MongoDB connection string. Supports authentication and replica sets. |
| `SERVER PORT`       | `8080`                           | Port used by Nexus Core for internal HTTP health checks.                  |
| `NEXUS_SIGNING_KEY` | *(environment variable)*         | Shared secret used for HMAC packet signing. **Required in production.**   |

---

## ✅ Best Practices

### 1. Keep Addon IDs in a constants file

```java
public final class AddonIds {
    public static final int PLAYER_STATS = 100;
    public static final int GUILDS       = 200;
    public static final int ECONOMY      = 300;
    public static final int EXAMPLE      = 500;
}
```

### 2. Use UUIDs as ID fields whenever possible

String names are not guaranteed to remain unique over time. If a player changes their name, duplicate documents may be created. Prefer `uuid` for `isId = true`.

### 3. Keep `handleRequest()` fast

This method runs synchronously before processing. Every blocking call here delays the entire request. Only perform validation — do not perform I/O.

### 4. Use distinctive `cacheKeyHeaderTag` values

```java
// ✅ Good
public String cacheKeyHeaderTag() { return "pvp_stats"; }

// ❌ Risky — may conflict with another addon using "stats"
public String cacheKeyHeaderTag() { return "stats"; }
```

### 5. Never call MongoDB directly from a Spigot plugin

The entire purpose of Nexus Core is centralized data access. Bypassing it breaks both cache consistency and the intended architecture.

### 6. Always define `NEXUS_SIGNING_KEY` in production

When the key is not configured, signature verification is disabled and the network becomes vulnerable to forged or replayed packets. Never hardcode the key in source code or version control; deploy it through a secrets manager or environment variable.

### 7. Keep system clocks synchronized (NTP)

Timestamp validation depends on all server clocks remaining reasonably close to each other. Clock drift may cause valid packets to fall outside the 5-minute window and be rejected.

---

## 🛡️ License

**Mustafa Bingül — All Rights Reserved**

---

# ⬡ NEXUS CORE

*Built for performance. Designed for scale.*
