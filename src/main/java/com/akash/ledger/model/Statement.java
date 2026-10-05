package com.akash.ledger.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Statement(
        UUID accountId,
        OffsetDateTime from,
        OffsetDateTime to,
        long openingBalanceMinor,
        long closingBalanceMinor,
        List<StatementEntry> entries,
        String nextCursor
) {
}
