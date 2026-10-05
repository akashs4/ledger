package com.akash.ledger.rest.dto.response;

import java.time.OffsetDateTime;

public record AccountStatementEntryDto(
        String transferId,
        OffsetDateTime transactionTime,
        String transactionDescription,
        String counterpartyAccountId,
        long transactionAmountMinor,
        boolean isCredit,
        long runningBalanceMinor
) {
}
