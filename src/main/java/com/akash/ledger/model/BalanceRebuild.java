package com.akash.ledger.model;

import java.util.List;

public record BalanceRebuild(
        int accountsRebuilt,
        int accountsCorrected,
        List<BalanceMismatch> corrections,
        boolean consistentAfterRebuild
) {
}
