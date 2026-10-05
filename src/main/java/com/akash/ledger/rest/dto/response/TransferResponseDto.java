package com.akash.ledger.rest.dto.response;

public record TransferResponseDto(
        String transferId,
        String requestId,
        String fromAccountId,
        String toAccountId,
        long amountMinor,
        String status,
        String rejectionReason
) {
}
