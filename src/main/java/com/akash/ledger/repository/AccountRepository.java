package com.akash.ledger.repository;

import com.akash.ledger.exception.AccountNotFoundException;
import com.akash.ledger.jooq.tables.records.AccountsRecord;
import com.akash.ledger.model.Account;
import com.akash.ledger.repository.mapper.RecordToModelMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.akash.ledger.jooq.Tables.ACCOUNTS;
import static com.akash.ledger.repository.mapper.RecordToModelMapper.toAccount;

@Repository
public class AccountRepository {
    private final DSLContext dslContext;

    public AccountRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Account openAccount(String name) {
        UUID accountId = UUID.randomUUID();
        AccountsRecord record = dslContext.insertInto(ACCOUNTS)
                .set(ACCOUNTS.ID, accountId)
                .set(ACCOUNTS.NAME, name)
                .returning()
                .fetchOptional()
                .orElseThrow(() -> new IllegalStateException("Account insert returned no row"));
        return toAccount(record);
    }

    public List<Account> findAll() {
        return dslContext.selectFrom(ACCOUNTS)
                .fetch(RecordToModelMapper::toAccount);
    }

    public Account getById(String accountId) {
        AccountsRecord record = dslContext.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(UUID.fromString(accountId)))
                .fetchOptional()
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        return toAccount(record);
    }
}
