package com.akash.ledger.rest.dto.response;

import java.util.UUID;

public record AccountResponseDto(
        UUID id,
        String name
) {
}
