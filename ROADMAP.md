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

| **v1.7.1** | **Minecraft SDK Release** | PaperMC & Spigot Engine | 📅 Planned 
| **v2.0.0** | **Unity Game Engine SDK** | Unity (C# / Multiplatform) | 📅 Planned |

---
> *Note: Features and priorities are subject to change based on community feedback.*
