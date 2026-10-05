package com.akash.ledger.rest.dto.response;

import java.util.List;

public record AccountsVerificationResponseDto(
        boolean consistent,
        int accountsChecked,
        int mismatchCount,
        long ledgerNetMinor,
        List<AccountMismatch> accountMismatches
) {
}
