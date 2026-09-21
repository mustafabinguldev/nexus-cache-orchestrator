# Java 26 migration

## Architecture and scope

The existing addon API, DataStore abstraction and Redis delivery protocol remain
in place. RequestExecution owns request context binding and completion tracking;
RedisManager handles dispatch, persistence and acknowledgement. ScopedValue is
stable (since Java 25); it replaces mutable ThreadLocal set/remove bookkeeping.
Ordinary executors do not inherit these bindings: track() explicitly binds the
same execution in every child task. The binding is immutable, while the shared
dirty-key set and completion counters remain concurrency-safe mutable state.

Spring Boot 4.1.1 supports Java 26, so a framework upgrade is unnecessary.
The web server now opts into virtual threads for blocking Redis/database calls.
This does not increase connection pool capacity or guarantee higher throughput.
The existing queue limits, delivery receipts and retry behavior remain intact.
No preview Structured Concurrency APIs or experimental JVM flags are required.

## Expected benefits and limits

Java 26 improves G1 synchronization overhead (JEP 522). Compared with Java 21,
virtual threads also benefit from the synchronized-block pinning improvements
introduced in Java 24 (JEP 491). Java 26 extends AOT object caching to all GCs
(JEP 516), but this project does not generate or enable an AOT cache automatically.
ScopedValue provides explicit lifetime and automatic binding cleanup; the
migration does not claim a measured speedup or lower latency.

Java 26 is a non-LTS release. Plan regular JDK upgrades or use a vendor support
policy appropriate to production. This build intentionally targets Java 26:
Java 21/25 cannot load the resulting classes. Direct library consumers need the
same runtime upgrade; clients communicating only through Redis do not.

## Validation and performance measurement

Run `mvn -B -ntp verify` using JDK 26. RequestExecutionTest covers nested scopes,
asynchronous failure, completion ordering, concurrent request isolation and
cleanup on a reused executor thread. For live Redis/Mongo/web checks, run
`diagnostics/run.ps1` with JAVA_HOME pointing to JDK 26 and the dedicated audit
containers described in diagnostics/README.md.

Compare equivalent datasets, JVM heap sizes, database/Redis pool settings and
concurrency levels. Warm up before measuring throughput, p95/p99 latency, heap,
GC pauses and CPU. Use `SPRING_THREADS_VIRTUAL_ENABLED=false` for a web-thread
comparison. Optional JFR recording:

```sh
java -XX:StartFlightRecording=filename=nexus.jfr,duration=120s,settings=profile -jar target/nexus-cache-orchestrato-1.7.0.1-boot.jar
```

Load-test staging with representative Redis traffic before sizing production.
No production-load benchmark or quantified performance improvement is claimed.

## Sources

- https://docs.spring.io/spring-boot/system-requirements.html
- https://docs.oracle.com/en/java/javase/26/migrate/significant-changes-jdk-26-release.html
- https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/ScopedValue.html
- https://openjdk.org/jeps/491
- https://www.oracle.com/java/technologies/java-se-support-roadmap.html
## Verified on 2026-09-21

- Windows / OpenJDK 26.0.2.1: Maven verify, 34 tests passed and boot JAR produced.
- Dedicated Redis/Mongo containers: 27 runtime checks, 8 web checks and 2 signature-policy checks passed.
- Docker Linux / Temurin 26: image `nexus-core:jdk26` built successfully with tests enabled.
- No production load benchmark or container VNC UI test was performed.