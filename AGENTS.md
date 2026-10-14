# ArcMounts repository guidance

- Keep the runtime plugin and `arc-mounts-api` as independently versioned artifacts.
- ARC owns and shades `ru.arc.mounts.api`; this plugin depends on it as `compileOnly`.
- Do not add a dependency on the full ARC plugin JAR. Depend only on public `arc-core` modules and server-provided APIs used by this plugin.
- Keep Paper, LuckPerms, PacketEvents, and other server-provided APIs `compileOnly`; never shade server classes.
- Do not package ARC-owned `ru.arc.paper.api` service contracts or the shared SQL, Redis, and metrics provider implementations. Keep the production JAR verifier in CI.
- Keep all `arc-core` coordinates on the single `core_version` in `arc-core-consumer.toml`.
- Public API changes require a new `mountsApiVersion`; published Maven coordinates are immutable. Plugin runtime version changes do not require an API version change.
- Before publication, CI must pass the central consumer verifier, unit tests, integration tests, deterministic shadow JAR checks, and immutable-coordinate preflight/readback.

Use JDK 25. Integration tests require Docker. Do not publish from local builds; the main-branch CI job publishes only after both test gates pass.
