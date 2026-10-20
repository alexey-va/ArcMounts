# ArcMounts

ArcMounts is the standalone Paper plugin for the RusCrafting mount collection.
ARC remains the runtime owner of the public mount service API and shared core
providers; this plugin consumes those services and owns mount gameplay.

The two immutable Maven coordinates have independent versions:

- Runtime plugin: `ru.ruscrafting:ArcMounts:0.1.15`
- Java API: `ru.ruscrafting.arc:arc-mounts-api:0.1.1`

ARC shades the API at runtime. Add-ons compile against the API and must not
embed its classes or the shared SQL, Redis, or metrics provider implementations.
Change `mountsApiVersion` before publishing any public API contract change;
never overwrite a published version. Runtime plugin releases can advance
without changing the API version.

## Build and verification

Requires JDK 25. Unit tests run with MockBukkit through `arc-core`'s published
Paper test kit. Integration tests use disposable services and require Docker.

```bash
./gradlew test
./gradlew integrationTest
./gradlew shadowJar
./scripts/verify-shadow-jar.sh
```

CI runs the central `arc-core` consumer verifier, unit tests, integration tests,
and the production JAR ownership check. On a green push to `main`, it publishes
the plugin JAR/POM and the API JAR, sources JAR, POM, and Gradle module metadata
to the public RusCrafting Maven repository. There is no GitHub Release step.

The local `arcCoreDir` Gradle property can point to an `arc-core` checkout for
composite development. CI uses the immutable `2.7.33` Maven release.
## Daily quest integration

With ArcRanks enabled, `MountQuestEligibility` exposes a cache-only ownership
lookup from the active catalog and effective LuckPerms cache: owned, none, or
unknown when the module/user cache is unavailable. It never loads user data or
performs persistence on the calling thread.

Actual controlled rides accumulate 3D movement with fractional carry. Native
vehicles use their entity position; visual flight uses the pilot position.
Invalid coordinates, world changes, teleports and steps above 16 blocks do not
count. Whole blocks are submitted about every five seconds and on dismount,
using `mounts_ride`, `mount.travel` and the same session UUID/sequence on retry.
The typed `arc-ranks-api:0.1.0` stays runtime-owned by ArcRanks. Writes are
asynchronous, with at most three retries after the first attempt. There is no
durable travel outbox: a crash/reload or unavailable provider can lose unrecorded
distance. No player ownership or mount reward is changed by this integration.
