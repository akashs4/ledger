package com.akash.ledger.rest.dto.response;

public record AccountMismatch(
        String accountId,
        Long cachedBalanceMinor,
        long derivedBalanceMinor,
        Long differenceMinor
) {
}
