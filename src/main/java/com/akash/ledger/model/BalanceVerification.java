package com.akash.ledger.model;

import java.util.List;

public record BalanceVerification(
        boolean consistent,
        int accountsChecked,
        int mismatchCount,
        long ledgerNetMinor,
        List<BalanceMismatch> mismatches
) {
}
