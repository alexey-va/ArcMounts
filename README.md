# ArcMounts

ArcMounts is the standalone Paper plugin for the RusCrafting mount collection.
ARC remains the runtime owner of the public mount service API and shared core
providers; this plugin consumes those services and owns mount gameplay.

The two immutable Maven coordinates have independent versions:

- Runtime plugin: `ru.ruscrafting:ArcMounts:0.1.4`
- Java API: `ru.ruscrafting.arc:arc-mounts-api:0.1.0`

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
composite development. CI uses the immutable `2.7.30` Maven release.
