package ru.arc.mounts.api;

import java.nio.file.Path;
/** Dynamic host state needed to preserve ARC-owned configuration and shared Redis lifecycle. */
public interface ArcMountConfiguration {
    Path dataPath();

    String serverName();

    boolean sharedRedisAvailable();

    long sharedRedisGeneration();
}
