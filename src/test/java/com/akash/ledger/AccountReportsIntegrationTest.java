package com.akash.ledger;

import com.akash.ledger.exception.AccountNotFoundException;
import com.akash.ledger.model.Account;
import com.akash.ledger.model.BalanceMismatch;
import com.akash.ledger.model.BalanceRebuild;
import com.akash.ledger.model.BalanceVerification;
import com.akash.ledger.model.Statement;
import com.akash.ledger.model.StatementEntry;
import com.akash.ledger.model.TransferCommand;
import com.akash.ledger.service.AccountService;
import com.akash.ledger.service.TransferService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.akash.ledger.jooq.Tables.BALANCES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountReportsIntegrationTest {

    private static final String SYSTEM_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private DSLContext dslContext;

    @AfterEach
    void ledgerIsConsistent() {
        assertTrue(accountService.verifyBalances().consistent(), "test left the ledger inconsistent");
    }

    @Test
    void verify_reportsHealthyLedgerAsConsistent() {
        Account alice = fundedAccount(5_000L);
        transfer(alice, fundedAccount(0L), 2_000L);

        BalanceVerification verification = accountService.verifyBalances();

        assertTrue(verification.consistent());
        assertEquals(0, verification.mismatchCount());
        assertEquals(0L, verification.ledgerNetMinor());
        assertTrue(verification.accountsChecked() >= 3);
    }

    @Test
    void verify_detectsCorruptedBalance_andRebuildRepairsIt() {
        Account alice = fundedAccount(5_000L);
        dslContext.update(BALANCES).set(BALANCES.BALANCE_MINOR, 4_000L).where(BALANCES.ACCOUNT_ID.eq(alice.id())).execute();

        BalanceVerification broken = accountService.verifyBalances();
        assertFalse(broken.consistent());
        assertEquals(1, broken.mismatchCount());
        assertEquals(-1_000L, broken.ledgerNetMinor());
        BalanceMismatch mismatch = broken.mismatches().getFirst();
        assertEquals(alice.id(), mismatch.accountId());
        assertEquals(4_000L, mismatch.cachedBalanceMinor());
        assertEquals(5_000L, mismatch.derivedBalanceMinor());

        BalanceRebuild rebuild = accountService.rebuildBalances();

        assertEquals(1, rebuild.accountsCorrected());
        assertEquals(alice.id(), rebuild.corrections().getFirst().accountId());
        assertTrue(rebuild.consistentAfterRebuild());
        assertEquals(5_000L, balance(alice));
    }

    @Test
    void verify_detectsMissingBalanceRow_andRebuildRecreatesIt() {
        Account alice = fundedAccount(0L);
        dslContext.deleteFrom(BALANCES).where(BALANCES.ACCOUNT_ID.eq(alice.id())).execute();

        BalanceVerification broken = accountService.verifyBalances();
        assertFalse(broken.consistent());
        assertNull(broken.mismatches().getFirst().cachedBalanceMinor());

        accountService.rebuildBalances();

        assertEquals(0L, balance(alice));
    }

    @Test
    void rebuild_keepsAccountsWithoutTransfers() {
        Account fresh = fundedAccount(0L);

        BalanceRebuild rebuild = accountService.rebuildBalances();

        assertEquals(0, rebuild.accountsCorrected());
        assertEquals(0L, balance(fresh));
    }

    @Test
    void statement_listsCreditsAndDebitsWithRunningBalance() {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(0L);
        transfer(alice, bob, 3_000L);

        Statement statement = statementOf(alice, null, 100);

        assertEquals(0L, statement.openingBalanceMinor());
        assertEquals(7_000L, statement.closingBalanceMinor());
        assertNull(statement.nextCursor());

        List<StatementEntry> entries = statement.entries();
        assertEquals(2, entries.size());
        assertEquals(10_000L, entries.get(0).line().amountMinor());
        assertEquals(UUID.fromString(SYSTEM_ACCOUNT_ID), entries.get(0).line().counterpartyId());
        assertEquals(10_000L, entries.get(0).runningBalanceMinor());
        assertEquals(-3_000L, entries.get(1).line().amountMinor());
        assertEquals(bob.id(), entries.get(1).line().counterpartyId());
        assertEquals(7_000L, entries.get(1).runningBalanceMinor());
    }

    @Test
    void statement_paginatesWithCursorAndKeepsRunningBalanceAcrossPages() {
        Account bob = fundedAccount(0L);
        for (int i = 0; i < 5; i++)
            fund(bob, 100L);

        Statement page1 = statementOf(bob, null, 2);
        Statement page2 = statementOf(bob, page1.nextCursor(), 2);
        Statement page3 = statementOf(bob, page2.nextCursor(), 2);

        assertEquals(List.of(100L, 200L), runningBalances(page1));
        assertEquals(List.of(300L, 400L), runningBalances(page2));
        assertEquals(List.of(500L), runningBalances(page3));
        assertNotNull(page1.nextCursor());
        assertNotNull(page2.nextCursor());
        assertNull(page3.nextCursor());
    }

    @Test
    void statement_forRangeAfterAllActivity_isEmptyWithOpeningEqualToBalance() {
        Account alice = fundedAccount(2_500L);
        OffsetDateTime from = OffsetDateTime.now().plusHours(1);

        Statement statement = accountService.getStatement(id(alice), from, from.plusHours(1), null, 100);

        assertTrue(statement.entries().isEmpty());
        assertEquals(2_500L, statement.openingBalanceMinor());
        assertEquals(2_500L, statement.closingBalanceMinor());
    }

    @Test
    void statement_rejectsInvertedRangeAndUnknownAccount() {
        Account alice = fundedAccount(0L);
        OffsetDateTime now = OffsetDateTime.now();

        assertThrows(IllegalArgumentException.class,
                () -> accountService.getStatement(id(alice), now, now.minusDays(1), null, 100));
        assertThrows(AccountNotFoundException.class,
                () -> accountService.getStatement(UUID.randomUUID().toString(), now.minusDays(1), now, null, 100));
    }

    private Statement statementOf(Account account, String cursor, int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        return accountService.getStatement(id(account), now.minusHours(1), now.plusHours(1), cursor, limit);
    }

    private static List<Long> runningBalances(Statement statement) {
        return statement.entries().stream().map(StatementEntry::runningBalanceMinor).toList();
    }

    private Account fundedAccount(long amountMinor) {
        Account account = accountService.openAccount("acct-" + UUID.randomUUID());
        if (amountMinor > 0)
            fund(account, amountMinor);
        return account;
    }

    private void fund(Account account, long amountMinor) {
        transferService.postTransfer(new TransferCommand(
                UUID.fromString(SYSTEM_ACCOUNT_ID), account.id(), UUID.randomUUID().toString(), amountMinor));
    }

    private void transfer(Account from, Account to, long amountMinor) {
        transferService.postTransfer(new TransferCommand(from.id(), to.id(), UUID.randomUUID().toString(), amountMinor));
    }

    private long balance(Account account) {
        return accountService.getBalance(id(account));
    }

    private static String id(Account account) {
        return account.id().toString();
    }
}
