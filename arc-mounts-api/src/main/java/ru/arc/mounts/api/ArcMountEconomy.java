package ru.arc.mounts.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Host-owned, exact currency adapter. Mutations are never implicitly retried. */
public interface ArcMountEconomy {
    boolean available(String currency);

    Long balanceMinor(String currency, UUID playerId);

    MoneyEvidence withdraw(
            String currency,
            UUID playerId,
            long amountMinor,
            String reason,
            long expectedBeforeMinor);

    MoneyEvidence deposit(
            String currency,
            UUID playerId,
            long amountMinor,
            String reason,
            long expectedBeforeMinor);

    CompletableFuture<TransactionEvidence> findTransaction(
            String currency,
            UUID playerId,
            long amountMinor,
            String reason,
            long notBeforeMillis);

    record MoneyEvidence(
            Boolean providerAccepted,
            boolean providerCallAttempted,
            Long balanceAfterMinor,
            String failureCode) {}

    record TransactionEvidence(String transactionId, boolean historyAvailable) {}
}
