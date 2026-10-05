package com.akash.ledger.repository;

import com.akash.ledger.jooq.tables.Accounts;
import com.akash.ledger.model.StatementLine;
import com.akash.ledger.model.TransferStatus;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.akash.ledger.jooq.Tables.ACCOUNTS;
import static com.akash.ledger.jooq.Tables.TRANSFERS;

@Repository
public class StatementRepository {

    private static final Condition POSTED = TRANSFERS.STATUS.eq(TransferStatus.POSTED.name());

    private final DSLContext dslContext;

    public StatementRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public record HistoryPosition(OffsetDateTime createdAt, UUID transferId) {
    }

    public Optional<HistoryPosition> findPosition(UUID accountId, UUID transferId) {
        return dslContext.select(TRANSFERS.CREATED_AT, TRANSFERS.ID)
                .from(TRANSFERS)
                .where(TRANSFERS.ID.eq(transferId).and(involves(accountId)).and(POSTED))
                .fetchOptional(r -> new HistoryPosition(r.value1(), r.value2()));
    }

    public long balanceBefore(UUID accountId, OffsetDateTime instant) {
        return signedSum(accountId, TRANSFERS.CREATED_AT.lt(instant));
    }

    public long balanceThrough(UUID accountId, HistoryPosition position) {
        return signedSum(accountId,
                DSL.row(TRANSFERS.CREATED_AT, TRANSFERS.ID).le(position.createdAt(), position.transferId()));
    }

    public List<StatementLine> findStatementLines(UUID accountId, OffsetDateTime from, OffsetDateTime to,
                                                  HistoryPosition after, int limit) {
        Accounts counterparty = ACCOUNTS.as("counterparty");
        Field<UUID> counterpartyId = DSL.when(TRANSFERS.FROM_ACCOUNT_ID.eq(accountId), TRANSFERS.TO_ACCOUNT_ID)
                .otherwise(TRANSFERS.FROM_ACCOUNT_ID);

        Condition condition = involves(accountId)
                .and(POSTED)
                .and(TRANSFERS.CREATED_AT.ge(from))
                .and(TRANSFERS.CREATED_AT.lt(to));
        if (after != null)
            condition = condition.and(DSL.row(TRANSFERS.CREATED_AT, TRANSFERS.ID).gt(after.createdAt(), after.transferId()));

        return dslContext.select(TRANSFERS.ID, counterparty.ID, counterparty.NAME, signedAmount(accountId), TRANSFERS.CREATED_AT)
                .from(TRANSFERS)
                .join(counterparty).on(counterparty.ID.eq(counterpartyId))
                .where(condition)
                .orderBy(TRANSFERS.CREATED_AT, TRANSFERS.ID)
                .limit(limit)
                .fetch(r -> new StatementLine(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }

    private long signedSum(UUID accountId, Condition condition) {
        Long sum = dslContext.select(DSL.sum(signedAmount(accountId)))
                .from(TRANSFERS)
                .where(involves(accountId).and(POSTED).and(condition))
                .fetchOne(0, Long.class);
        return sum == null ? 0L : sum;
    }

    private static Field<Long> signedAmount(UUID accountId) {
        return DSL.when(TRANSFERS.TO_ACCOUNT_ID.eq(accountId), TRANSFERS.AMOUNT_MINOR)
                .otherwise(TRANSFERS.AMOUNT_MINOR.neg());
    }

    private static Condition involves(UUID accountId) {
        return TRANSFERS.FROM_ACCOUNT_ID.eq(accountId).or(TRANSFERS.TO_ACCOUNT_ID.eq(accountId));
    }
}
