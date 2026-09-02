# ⬡ Nexus Cache Orchestrator

**A central data orchestration layer for distributed server networks, built on Redis Pub/Sub, MongoDB, and a Spring-powered management interface.**

> *"The single source of truth between your distributed infrastructure and your persistent data layer."*

[![Java](https://img.shields.io/badge/Java-17+-blue?style=flat-square&logo=java)](https://www.java.com)
[![Spring](https://img.shields.io/badge/Spring-Boot-brightgreen?style=flat-square&logo=spring)](https://spring.io)
[![Redis](https://img.shields.io/badge/Redis-Message%20Broker-red?style=flat-square&logo=redis)](https://redis.io)
[![MongoDB](https://img.shields.io/badge/MongoDB-Persistence-green?style=flat-square&logo=mongodb)](https://www.mongodb.com)
[![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)](#-license)

---

## 📖 Table of Contents

- [What is Nexus?](#-what-is-nexus)
- [System Architecture](#-system-architecture)
- [Data Flow](#-data-flow)
- [Core Components](#-core-components)
- [Web Interface (Spring)](#-web-interface-spring)
- [Getting Started](#-getting-started)
- [Writing an Addon](#-writing-an-addon)
- [DataAddon API Reference](#-dataaddon-api-reference)
- [Protocol & Packet Structure](#-protocol--packet-structure)
- [Redis Key Strategy](#-redis-key-strategy)
- [Configuration](#-configuration)
- [Best Practices](#-best-practices)
- [Version History](#-version-history)
- [License](#-license)

---

## 🧠 What is Nexus?

**Nexus Cache Orchestrator** is a standalone Java application that acts as the **central orchestration layer** for a distributed server network. Instead of every node connecting directly to MongoDB and managing its own cache, all data operations are delegated to Nexus through a **Redis Pub/Sub message bus**.

This design solves three recurring problems at scale:

| Problem | Without Nexus | With Nexus |
|---|---|---|
| **Database connections** | Every node holds its own connection pool | A single, optimized connection pool |
| **Cache coherency** | Each node keeps its own, potentially stale cache | One centralized Redis cache, always consistent |
| **Data logic duplication** | Repeated across every node's codebase | Defined once in a `DataAddon`, shared everywhere |

Nexus behaves like a purpose-built **microservice** for your infrastructure: it owns all data, while every connected node acts as a thin, stateless client.

---

## 🏗️ System Architecture

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Node #1    │    │   Node #2    │    │   Node #3    │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                            │  Redis Pub/Sub
                  ┌─────────▼──────────┐
                  │    Redis Broker     │
                  │   (Message Bus)     │
                  └─────────┬──────────┘
                            │
                  ┌─────────▼──────────┐
                  │   ⬡ NEXUS CORE ⬡    │
                  │                    │
                  │  ┌──────────────┐  │
                  │  │ProtocolHandler│  │
                  │  │  Addon #100  │  │
                  │  │  Addon #200  │  │
                  │  │  Addon #500  │  │
                  │  └──────┬───────┘  │
                  └─────────┼──────────┘
                            │  CRUD Operations
                  ┌─────────▼──────────┐
                  │      MongoDB        │
                  │   nexus_core_db     │
                  └────────────────────┘
```

Nexus itself exposes a **Spring-based management console** alongside the orchestration engine, giving operators visibility into the running system without having to read raw logs (see [Web Interface](#-web-interface-spring)).

---

## 🔄 Data Flow

Every interaction follows a strict **request → orchestrate → respond** lifecycle:

1. A node **publishes** a packet on Redis containing a `protocol` ID, a `source` identifier, a `type` (e.g. `GET_DATA`), and a `data` payload.
2. Redis routes the message to the Nexus listener.
3. Nexus resolves the target `DataAddon` from the `ProtocolHandler` registry using the `protocol` ID.
4. `handleRequest()` runs as a pre-check; it can reject the operation before any I/O happens.
5. Nexus checks the **Redis (L2) cache** first.
    - On a cache **hit**, the cached document is returned immediately.
    - On a cache **miss**, Nexus queries **MongoDB**, then writes the result back into the cache.
6. The response is serialized and published back to the requesting node's channel.

### Supported Request Types

| Type | Description | MongoDB Operation | Cache Behavior |
|---|---|---|---|
| `GET_DATA` | Fetch a document by its ID field | `findOne()` | Read-through |
| `SET_DATA` | Insert or fully replace a document | `replaceOne(upsert)` | Write-through |
| `UPDATE_DATA` | Partially update fields on an existing document | `updateOne()` | Write-through |
| `INCREMENT_DATA` | Atomically increment a numeric field | `findOne()` + mutate | Write-through |
| `REMOVE_DATA` | Delete a document by ID | `deleteOne()` | Invalidate |
| `LOAD_CACHE` | Pre-warm a document into memory | `findOne()` | Warm |
| `BROADCAST` | Push a payload to a target node over Redis | — | Bypass |

---

## 🧩 Core Components

### `NexusApplication`
The main entry point and bootstrapper. Initializes the Redis connection, MongoDB client, and loads every registered `DataAddon` into the registry. It is marked `volatile` to stay safe across highly concurrent, multi-threaded access, and exposes runtime metrics such as `getDataSize()` and `getAddonSize()` for the monitoring layer.

### `DataAddon`
The abstract base class that defines a **data module**. Each addon maps to exactly one MongoDB collection and one Redis key namespace. Extend this class whenever you need to introduce a new type of data into the network.

### `ProtocolHandler`
A thread-safe registry (`Map<Integer, DataAddon>`) keyed on `addonId()`. When a packet arrives with a given `protocol`, the handler resolves the matching addon in O(1) and delegates the operation to it.

### `NexusJsonDataContainer`
A lightweight wrapper around the JSON payload passed into `handleRequest()`, providing null-safe, typed accessors for reading values out of the incoming packet.

### Monitoring & Metrics
Nexus tracks JVM CPU load, heap usage, live cache object counts, and active addon counts in real time, feeding both the local dashboard and the web interface described below.

---

## 🖥️ Web Interface (Spring)

Nexus now ships with a **Spring-based web interface** that exposes the orchestrator's live state through a browser instead of requiring direct access to the host machine. It sits alongside the core Redis/MongoDB engine and gives operators a modern way to observe and manage the running instance:

- **Live system overview** — CPU load, JVM heap usage, cache object count, and active addon count, sourced from the same metrics the engine already tracks.
- **Addon registry view** — inspect which `DataAddon` instances are currently registered and which protocol IDs they own.
- **Connection status** — at-a-glance health of the Redis and MongoDB connections, including the watchdog/health-check state introduced in earlier releases.
- **Web-first access** — no need to be on the host machine or attached to the Swing dashboard to check on the system.

> ℹ️ The Spring layer is additive: it does not replace the Redis Pub/Sub protocol that nodes use to talk to Nexus — it is a management and observability surface on top of the same engine.

---

## 🚀 Getting Started

### Prerequisites

- Java 17+
- A running Redis instance (`redis-server`)
- A running MongoDB instance (`mongod`)
- Maven

### Build & Run

```bash
# Clone the repository
git clone https://github.com/mustafabinguldev/nexus-cache-orchestrator.git
cd nexus-cache-orchestrator

# Build with Maven
mvn clean package -DskipTests

# Run the application
java -jar target/nexus-cache-orchestrator.jar
```

On first launch, provide your Redis host, MongoDB URI, and server port. Once configured, these values are persisted, and subsequent startups connect automatically without manual input.

### Default Configuration

| Field | Default |
|---|---|
| Redis Host | `127.0.0.1` |
| MongoDB URI | `mongodb://localhost:27017` |
| Server Port | `8080` |

---

## 🛠️ Writing an Addon

An addon is the fundamental unit of data in Nexus. Each addon represents a single collection of documents, declares its schema through annotations, and can optionally intercept requests before they're processed.

### Step 1 — Extend `DataAddon`

```java
package your.package.addons;

import network.core.protocol.DataAddon;
import network.core.protocol.NexusJsonDataContainer;
import network.core.protocol.backup.annotations.DbDataModels;

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
        return "player_stats";
    }

    @Override
    public String cacheKeyHeaderTag() {
        return "stats"; // Redis key: "stats:<id field value>"
    }

    @DbDataModels(isId = true)
    private String uuid;

    @DbDataModels(defaultValue = "0", isId = false)
    private int kills;

    @DbDataModels(defaultValue = "0", isId = false)
    private int deaths;

    @DbDataModels(defaultValue = "0.0", isId = false)
    private double balance;

    @Override
    public boolean handleRequest(String source, RequestType requestType, NexusJsonDataContainer data) {
        if (requestType == RequestType.REMOVE_DATA) {
            return source.equals("admin"); // Only allow deletes from the admin node
        }
        return true;
    }
}
```

### Step 2 — Register the Addon

```java
NexusApplication.getInstance().getProtocolHandler().registerAddon(new PlayerStatsAddon());
```

That's it — Nexus will now automatically handle `GET_DATA`, `SET_DATA`, `UPDATE_DATA`, `INCREMENT_DATA`, and `REMOVE_DATA` requests for protocol `100`, routing them to the `player_stats` MongoDB collection with full Redis caching.

---

## 📚 DataAddon API Reference

| Method | Purpose |
|---|---|
| `addonId()` | Unique numeric protocol ID this addon owns |
| `addonName()` | Human-readable name shown in logs and the web interface |
| `getDatabase()` | Target MongoDB database |
| `getCollection()` | Target MongoDB collection |
| `cacheKeyHeaderTag()` | Prefix for Redis cache keys (`{tag}:{idValue}`) |
| `getCacheTTL()` | Per-addon time-to-live for cached entries |
| `handleRequest(...)` | Pre-processing hook; return `false` to reject a request |

### `@DbDataModels` Annotation

| Parameter | Type | Required | Description |
|---|---|---|---|
| `isId` | `boolean` | ✅ | Marks the field as the primary key. Exactly one field per addon must set this to `true`. |
| `defaultValue` | `String` | ✅ if `isId = false` | Value written when the field is missing from the incoming packet. |

Supported field types: `String`, `int`/`Integer`, `long`/`Long`, `double`/`Double`, `boolean`/`Boolean`.

---

## 📦 Protocol & Packet Structure

**Inbound packet (Node → Redis → Nexus):**

```json
{
  "protocol": 100,
  "source": "node-1",
  "type": "GET_DATA",
  "data": { "uuid": "550e8400-e29b-41d4-a716-446655440000" }
}
```

**Outbound packet (Nexus → Redis → Node):**

```json
{
  "protocol": 100,
  "source": "nexus",
  "type": "BROADCAST",
  "target": "node-1",
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "kills": 142,
    "deaths": 38,
    "balance": 2500.75
  }
}
```

---

## 🗝️ Redis Key Strategy

```
{cacheKeyHeaderTag}_{idFieldValue}
```

| Addon | `cacheKeyHeaderTag` | ID Value | Full Redis Key |
|---|---|---|---|
| `PlayerStatsAddon` | `stats` | `Steve` | `stats:Steve` |
| `GuildAddon` | `guild` | `Darkland` | `guild:Darkland` |
| `EconomyAddon` | `eco` | `550e8400...` | `eco:550e8400...` |

> ⚠️ **Warning:** Two addons sharing the same `cacheKeyHeaderTag` will overwrite each other's cache entries — always use unique prefixes.

Cache entries are stored as serialized JSON and are automatically invalidated on `SET`/`UPDATE`/`REMOVE` operations, with Redis Keyspace Notifications keeping the in-memory (L1) layer in sync when keys expire.

---

## ⚙️ Configuration

| Field | Example | Notes |
|---|---|---|
| `REDIS_HOST` | `127.0.0.1` | Hostname or IP of the Redis server. Port defaults to `6379`. |
| `MONGO_URI` | `mongodb://user:pass@host:27017` | Full MongoDB connection string; supports auth and replica sets. |
| `SERVER_PORT` | `8080` | Port used by internal health checks and the web interface. |

Connection parameters are validated at boot and persisted after the first successful setup, enabling automatic reconnection on subsequent starts.

---

## ✅ Best Practices

**1. Keep addon IDs in a constants file**
```java
public final class AddonIds {
    public static final int PLAYER_STATS = 100;
    public static final int GUILDS       = 200;
    public static final int ECONOMY      = 300;
}
```

**2. Prefer UUIDs over names as ID fields** — display names can change over time and create duplicate documents.

**3. Keep `handleRequest()` fast** — it runs synchronously before the operation. Do validation only; never perform blocking I/O here.

**4. Use distinct, namespaced `cacheKeyHeaderTag` values** to avoid collisions across addons and databases.

**5. Never bypass Nexus** — connecting directly to MongoDB from a node defeats cache coherency and the entire purpose of the architecture.

---

## 🕘 Version History

| Version | Highlights |
|---|---|
| **1.4.2** | Thread-safety fixes for `NexusApplication`, `try-with-resources` for class loading, MongoDB race-condition fix, safer `Optional`-based metrics handling |
| **1.4.1** | MongoDB connection timeouts, periodic health-check watchdog, fail-safe shutdown on critical DB failure |
| **1.4.0** | Persistent connection configuration, zero-touch startup, global ranking & rank-finder protocols |
| **1.3.0** | Real-time sync protocols, per-addon cache TTL, Redis Keyspace Notification-based L1/L2 invalidation, sliding expiration |
| **1.2.0** | `INCREMENT_DATA` protocol, shared `ObjectMapper`, reflection caching, concurrency and null-safety fixes |
| **1.1.0** | Redis-master cache hierarchy, TTL eviction recovery, batched reconciliation, O(1) ID lookups |
| **1.0.0** | First stable release |

---

## 🛡️ License

**MIT License** — see [LICENSE](./LICENSE) for details.

---

**⬡ NEXUS CACHE ORCHESTRATOR**
*Built for performance. Designed for scale.*