package com.akash.ledger.model;

import java.util.UUID;

public record Account(
        UUID id,
        String name
) {
}
