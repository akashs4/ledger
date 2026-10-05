package com.akash.ledger.rest.dto.response;

import java.util.List;

public record BalanceRebuildResponseDto(
        int accountsRebuilt,
        int accountsCorrected,
        List<AccountMismatch> corrections,
        boolean consistentAfterRebuild
) {
}
