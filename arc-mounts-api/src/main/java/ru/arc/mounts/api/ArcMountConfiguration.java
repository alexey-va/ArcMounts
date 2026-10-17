package ru.arc.mounts.api;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
/** Dynamic host state needed to preserve ARC-owned configuration and shared Redis lifecycle. */
public interface ArcMountConfiguration {
    /** Completes after this host generation has prepared configuration and attached native services. */
    default CompletableFuture<Void> ready() {
        return CompletableFuture.completedFuture(null);
    }

    Path dataPath();

    String serverName();

    boolean sharedRedisAvailable();

    long sharedRedisGeneration();
}
