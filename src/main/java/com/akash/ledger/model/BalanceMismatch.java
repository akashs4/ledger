package com.akash.ledger.model;

import java.util.UUID;

public record BalanceMismatch(
        UUID accountId,
        Long cachedBalanceMinor,
        long derivedBalanceMinor
) {
}
