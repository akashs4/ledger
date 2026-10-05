package com.akash.ledger.model;

public record StatementEntry(
        StatementLine line,
        long runningBalanceMinor
) {
}
