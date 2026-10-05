package com.akash.ledger.service;

import com.akash.ledger.model.Account;
import com.akash.ledger.model.BalanceRebuild;
import com.akash.ledger.model.BalanceVerification;
import com.akash.ledger.model.Statement;
import com.akash.ledger.model.StatementEntry;
import com.akash.ledger.model.StatementLine;
import com.akash.ledger.repository.AccountRepository;
import com.akash.ledger.repository.BalanceRepository;
import com.akash.ledger.repository.BalanceRepository.VerificationSummary;
import com.akash.ledger.repository.StatementRepository;
import com.akash.ledger.repository.StatementRepository.HistoryPosition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    static final int MAX_REPORTED_MISMATCHES = 100;
    static final int MAX_STATEMENT_PAGE_SIZE = 500;

    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final StatementRepository statementRepository;

    public AccountService(AccountRepository accountRepository, BalanceRepository balanceRepository,
                          StatementRepository statementRepository) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.statementRepository = statementRepository;
    }

    @Transactional
    public Account openAccount(String name) {
        Account account = accountRepository.openAccount(name);
        balanceRepository.openZeroBalance(account.id());
        return account;
    }

    public List<Account> findAll() {
        return accountRepository.findAll();
    }

    public long getBalance(String id) {
        return balanceRepository.getBalance(UUID.fromString(id));
    }

    public Account getById(String accountId) {
        return accountRepository.getById(accountId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BalanceVerification verifyBalances() {
        return verify();
    }

    @Transactional
    public BalanceRebuild rebuildBalances() {
        balanceRepository.lockAgainstWrites();
        BalanceVerification before = verify();
        int rebuilt = balanceRepository.rebuildFromTransfers();
        BalanceVerification after = verify();
        return new BalanceRebuild(rebuilt, before.mismatchCount(), before.mismatches(), after.consistent());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statement getStatement(String accountId, OffsetDateTime from, OffsetDateTime to, String cursor, int limit) {
        if (!from.isBefore(to))
            throw new IllegalArgumentException("'from' must be before 'to'");
        if (limit < 1 || limit > MAX_STATEMENT_PAGE_SIZE)
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_STATEMENT_PAGE_SIZE);

        UUID id = accountRepository.getById(accountId).id();
        long opening = statementRepository.balanceBefore(id, from);
        long closing = statementRepository.balanceBefore(id, to);

        HistoryPosition after = cursor == null ? null : statementRepository.findPosition(id, UUID.fromString(cursor))
                .orElseThrow(() -> new IllegalArgumentException("Unknown cursor for this account: " + cursor));
        long running = after == null ? opening : statementRepository.balanceThrough(id, after);

        List<StatementLine> lines = statementRepository.findStatementLines(id, from, to, after, limit + 1);
        boolean hasMore = lines.size() > limit;
        List<StatementLine> page = hasMore ? lines.subList(0, limit) : lines;

        List<StatementEntry> entries = new ArrayList<>(page.size());
        for (StatementLine line : page) {
            running += line.amountMinor();
            entries.add(new StatementEntry(line, running));
        }

        String nextCursor = hasMore ? page.getLast().transferId().toString() : null;
        return new Statement(id, from, to, opening, closing, entries, nextCursor);
    }

    private BalanceVerification verify() {
        VerificationSummary summary = balanceRepository.verificationSummary();
        boolean consistent = summary.mismatchCount() == 0 && summary.ledgerNetMinor() == 0;
        return new BalanceVerification(consistent, summary.accountsChecked(), summary.mismatchCount(),
                summary.ledgerNetMinor(), balanceRepository.findMismatches(MAX_REPORTED_MISMATCHES));
    }
}
