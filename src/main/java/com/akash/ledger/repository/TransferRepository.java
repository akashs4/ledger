package com.akash.ledger.repository;

import com.akash.ledger.model.Transfer;
import com.akash.ledger.model.TransferStatus;
import com.akash.ledger.repository.mapper.RecordToModelMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.akash.ledger.jooq.Tables.TRANSFERS;

@Repository
public class TransferRepository {
    private final DSLContext dslContext;

    public TransferRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Optional<Transfer> insertIfAbsent(String idempotencyKey, UUID fromAccountId, UUID toAccountId,
                                             long amountMinor, TransferStatus status, String rejectionReason) {
        return dslContext.insertInto(TRANSFERS)
                .set(TRANSFERS.ID, UUID.randomUUID())
                .set(TRANSFERS.IDEMPOTENCY_KEY, idempotencyKey)
                .set(TRANSFERS.FROM_ACCOUNT_ID, fromAccountId)
                .set(TRANSFERS.TO_ACCOUNT_ID, toAccountId)
                .set(TRANSFERS.AMOUNT_MINOR, amountMinor)
                .set(TRANSFERS.STATUS, status.name())
                .set(TRANSFERS.REJECTION_REASON, rejectionReason)
                .onConflict(TRANSFERS.IDEMPOTENCY_KEY)
                .doNothing()
                .returning()
                .fetchOptional()
                .map(RecordToModelMapper::toTransfer);
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return dslContext.selectFrom(TRANSFERS)
                .where(TRANSFERS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional()
                .map(RecordToModelMapper::toTransfer);
    }
}
