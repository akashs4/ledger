package com.akash.ledger.model;

import java.util.UUID;

public record Transfer(
        UUID id,
        String idempotencyKey,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        TransferStatus status,
        String rejectionReason
) {
}
