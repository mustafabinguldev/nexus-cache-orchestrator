# Nexus Cache Orchestrator - Project Roadmap

Here is an overview of the planned features, enhancements, and architectural goals for upcoming releases.


## 🚀 Version 1.6.5 — High-Performance Multi-Level Caching  (In Progress)
- [ ] **L1/L2 Multi-Level Caching:** Introduce an in-memory L1 cache layer (via Caffeine/Guava) combined with Redis as L2 to eliminate unnecessary network roundtrips.
- [ ] **Pub/Sub Cache Invalidation:** Real-time L1 invalidation messaging across distributed application nodes.
- [ ] **Multi-Tier Metrics:** Detailed telemetry tracking hit rates for both L1 local memory and L2 distributed cache.
- [ ] 
## 🚀 Version 1.7.0 — Multi-Database Ecosystem
- [ ] **Multi-Database Support:** Abstract core database drivers to support PostgreSQL, MySQL, and key-value stores natively.
- [ ] **Dynamic Connection Routing:** Enable context-aware database connection switching.
- [ ] **Unified Adapter Layer:** Standardize database queries across different engine providers.

## 📦 SDK & Platform Integrations

### 🟢 Version 1.7.1 — Minecraft Ecosystem Integration
- [ ] **Paper & Spigot SDKs:** Publish official SDK packages for Minecraft Java & Bedrock Edition server platforms (PaperMC / Spigot).
- [ ] **Plugin Data Persistence Layer:** Provide high-performance asynchronous caching wrappers for player data, inventories, and global server states.

### 🚀 Version 2.0.0 — Unity SDK & Game Backend Support
- [ ] **Unity SDK:** Deliver native C# / Unity SDK supporting cross-platform cache management (PC, Mobile, Console).
- [ ] **Real-time Session Caching:** Out-of-the-box orchestration for player sessions, matchmaking states, and live service configurations.

---
> *Note: Features and priorities are subject to change based on community feedback.*
