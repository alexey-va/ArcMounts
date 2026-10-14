package ru.arc.mounts.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Public host-to-mounts boundary. The child plugin registers one instance while enabled. */
public interface ArcMountService {
    boolean summonFavorite(Player player);

    Optional<ItemStack> rewardPreview(String mountId);

    CompletionStage<MountRewardOutcome> grantReward(Player player, String mountId);

    Optional<ActiveMountSnapshot> activeMountSnapshot(Player player);

    CompletionStage<MountCareBoostStatus> dailyCareBoostStatus(UUID playerId);

    CompletionStage<MountCareBoostClaimOutcome> claimDailyCareBoost(UUID playerId, UUID requestId);

    void reload();

    int purchaseJournalSchema();

    record ActiveMountSnapshot(String mountId, String mountName, String rarity, UUID entityId) {}

    record MountRewardOutcome(
            Result result,
            String mountId,
            int ownedLevel,
            Rejection rejection) {
        public enum Result {
            GRANTED,
            ALREADY_OWNED,
            REJECTED
        }

        public enum Rejection {
            MODULE_UNAVAILABLE,
            SHUTDOWN,
            UNKNOWN_MOUNT,
            PLAYER_OFFLINE,
            BUSY,
            ALREADY_PENDING,
            OWNERSHIP_READ_FAILED,
            GRANT_UNCERTAIN
        }
    }

    record MountCareBoostRecord(
            UUID requestId,
            long grantedAtMillis,
            long expiresAtMillis,
            long nextClaimAtMillis,
            double multiplier) {}

    record MountCareBoostStatus(
            Optional<MountCareBoostRecord> record,
            boolean active,
            long remainingMillis,
            long nextClaimInMillis) {}

    record MountCareBoostClaimOutcome(
            Claim result,
            MountCareBoostRecord record,
            boolean sameRequest) {
        public enum Claim {
            GRANTED,
            ALREADY_GRANTED
        }
    }
}
