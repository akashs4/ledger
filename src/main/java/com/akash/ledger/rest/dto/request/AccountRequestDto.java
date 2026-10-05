package com.akash.ledger.rest.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AccountRequestDto(
        @NotBlank String name
) {
}
