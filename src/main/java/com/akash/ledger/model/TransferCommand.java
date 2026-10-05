package com.akash.ledger.model;

import java.util.UUID;

public record TransferCommand(
        UUID fromAccountId,
        UUID toAccountId,
        String idempotencyKey,
        long amountMinor
) {
}
