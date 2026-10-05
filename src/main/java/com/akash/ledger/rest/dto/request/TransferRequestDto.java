package com.akash.ledger.rest.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record TransferRequestDto(
        @NotBlank String fromId,
        @NotBlank String toId,
        @NotBlank String requestId,
        @Positive long amountMinor
) {
}
