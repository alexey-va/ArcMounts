package ru.arc.mounts.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;

/** Optional host packet operations used by mounts; callers invoke these on Paper's primary thread. */
public interface ArcMountVisibility {
    boolean available();

    void setEntityInvisibleFor(LivingEntity entity, Player player, boolean invisible);

    void synchronizePassengersFor(Player player, List<LivingEntity> vehicles);
}
