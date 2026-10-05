package com.akash.ledger.repository;

import com.akash.ledger.exception.AccountNotFoundException;
import com.akash.ledger.model.BalanceMismatch;
import com.akash.ledger.model.TransferStatus;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.akash.ledger.jooq.Tables.ACCOUNTS;
import static com.akash.ledger.jooq.Tables.BALANCES;
import static com.akash.ledger.jooq.Tables.TRANSFERS;

@Repository
public class BalanceRepository {

    private static final Condition POSTED = TRANSFERS.STATUS.eq(TransferStatus.POSTED.name());

    private static final Table<?> MOVEMENTS = DSL
            .select(TRANSFERS.TO_ACCOUNT_ID.as("account_id"), TRANSFERS.AMOUNT_MINOR.as("delta"))
            .from(TRANSFERS).where(POSTED)
            .unionAll(DSL
                    .select(TRANSFERS.FROM_ACCOUNT_ID.as("account_id"), TRANSFERS.AMOUNT_MINOR.neg().as("delta"))
                    .from(TRANSFERS).where(POSTED))
            .asTable("movements");
    private static final Field<UUID> MOVEMENT_ACCOUNT_ID = MOVEMENTS.field("account_id", UUID.class);

    private static final Table<?> DERIVED_TOTALS = DSL
            .select(MOVEMENT_ACCOUNT_ID, DSL.sum(MOVEMENTS.field("delta", Long.class)).cast(SQLDataType.BIGINT).as("total"))
            .from(MOVEMENTS)
            .groupBy(MOVEMENT_ACCOUNT_ID)
            .asTable("derived_totals");
    private static final Field<UUID> TOTALS_ACCOUNT_ID = DERIVED_TOTALS.field("account_id", UUID.class);
    private static final Field<Long> DERIVED_BALANCE = DSL.coalesce(DERIVED_TOTALS.field("total", Long.class), 0L);
    private static final Condition BALANCE_MISMATCH = BALANCES.BALANCE_MINOR.isDistinctFrom(DERIVED_BALANCE);

    private final DSLContext dslContext;

    public BalanceRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public record LockedBalance(UUID accountId, long balanceMinor, boolean systemAccount) {
    }

    public record VerificationSummary(int accountsChecked, int mismatchCount, long ledgerNetMinor) {
    }

    public void openZeroBalance(UUID accountId) {
        dslContext.insertInto(BALANCES)
                .set(BALANCES.ACCOUNT_ID, accountId)
                .execute();
    }

    public long getBalance(UUID accountId) {
        return dslContext.select(BALANCES.BALANCE_MINOR)
                .from(BALANCES)
                .where(BALANCES.ACCOUNT_ID.eq(accountId))
                .fetchOptional(BALANCES.BALANCE_MINOR)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
    }

    public Map<UUID, LockedBalance> lockForUpdate(UUID first, UUID second) {
        return dslContext.select(BALANCES.ACCOUNT_ID, BALANCES.BALANCE_MINOR, ACCOUNTS.ACCOUNT_TYPE)
                .from(BALANCES)
                .join(ACCOUNTS).on(ACCOUNTS.ID.eq(BALANCES.ACCOUNT_ID))
                .where(BALANCES.ACCOUNT_ID.in(first, second))
                .orderBy(BALANCES.ACCOUNT_ID)
                .forUpdate().of(BALANCES)
                .fetchMap(BALANCES.ACCOUNT_ID, r -> new LockedBalance(r.value1(), r.value2(), "SYSTEM".equals(r.value3())));
    }

    public void adjust(UUID accountId, long deltaMinor) {
        dslContext.update(BALANCES)
                .set(BALANCES.BALANCE_MINOR, BALANCES.BALANCE_MINOR.plus(deltaMinor))
                .set(BALANCES.LAST_UPDATED, DSL.currentOffsetDateTime())
                .where(BALANCES.ACCOUNT_ID.eq(accountId))
                .execute();
    }

    public VerificationSummary verificationSummary() {
        return dslContext.select(
                        DSL.count(),
                        DSL.count().filterWhere(BALANCE_MISMATCH),
                        DSL.coalesce(DSL.sum(BALANCES.BALANCE_MINOR), BigDecimal.ZERO))
                .from(ACCOUNTS)
                .leftJoin(BALANCES).on(BALANCES.ACCOUNT_ID.eq(ACCOUNTS.ID))
                .leftJoin(DERIVED_TOTALS).on(TOTALS_ACCOUNT_ID.eq(ACCOUNTS.ID))
                .fetchSingle(r -> new VerificationSummary(r.value1(), r.value2(), r.value3().longValue()));
    }

    public List<BalanceMismatch> findMismatches(int limit) {
        return dslContext.select(ACCOUNTS.ID, BALANCES.BALANCE_MINOR, DERIVED_BALANCE)
                .from(ACCOUNTS)
                .leftJoin(BALANCES).on(BALANCES.ACCOUNT_ID.eq(ACCOUNTS.ID))
                .leftJoin(DERIVED_TOTALS).on(TOTALS_ACCOUNT_ID.eq(ACCOUNTS.ID))
                .where(BALANCE_MISMATCH)
                .orderBy(ACCOUNTS.ID)
                .limit(limit)
                .fetch(r -> new BalanceMismatch(r.value1(), r.value2(), r.value3()));
    }

    public void lockAgainstWrites() {
        dslContext.execute("LOCK TABLE balances IN EXCLUSIVE MODE");
    }

    public int rebuildFromTransfers() {
        dslContext.deleteFrom(BALANCES).execute();
        return dslContext.insertInto(BALANCES, BALANCES.ACCOUNT_ID, BALANCES.BALANCE_MINOR)
                .select(DSL.select(ACCOUNTS.ID, DERIVED_BALANCE)
                        .from(ACCOUNTS)
                        .leftJoin(DERIVED_TOTALS).on(TOTALS_ACCOUNT_ID.eq(ACCOUNTS.ID)))
                .execute();
    }
}
