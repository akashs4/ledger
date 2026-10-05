package com.akash.ledger.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StatementLine(
        UUID transferId,
        UUID counterpartyId,
        String counterpartyName,
        long amountMinor,
        OffsetDateTime createdAt
) {
}
