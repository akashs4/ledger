package com.akash.ledger.rest.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record AccountStatementResponseDto(
        String accountId,
        OffsetDateTime from,
        OffsetDateTime to,
        long openingBalanceMinor,
        long closingBalanceMinor,
        List<AccountStatementEntryDto> entries,
        String nextCursor
) {
}
