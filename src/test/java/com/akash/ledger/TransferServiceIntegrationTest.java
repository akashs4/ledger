package com.akash.ledger;

import com.akash.ledger.exception.AccountNotFoundException;
import com.akash.ledger.exception.IdempotencyKeyReuseException;
import com.akash.ledger.model.Account;
import com.akash.ledger.model.BalanceVerification;
import com.akash.ledger.model.Transfer;
import com.akash.ledger.model.TransferCommand;
import com.akash.ledger.model.TransferStatus;
import com.akash.ledger.service.AccountService;
import com.akash.ledger.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferServiceIntegrationTest {

    private static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @AfterEach
    void ledgerInvariantsHold() {
        BalanceVerification verification = accountService.verifyBalances();
        assertEquals(0L, verification.ledgerNetMinor(), "money was created or destroyed");
        assertEquals(List.of(), verification.mismatches(), "balances table drifted from the transfer log");
    }

    @Test
    void postTransfer_movesFundsBetweenAccounts() {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(0L);

        Transfer transfer = transfer(alice, bob, 3_000L);

        assertEquals(TransferStatus.POSTED, transfer.status());
        assertNull(transfer.rejectionReason());
        assertEquals(7_000L, balance(alice));
        assertEquals(3_000L, balance(bob));
    }

    @Test
    void postTransfer_recordsRejectionWhenInsufficientFunds() {
        Account alice = fundedAccount(500L);
        Account bob = fundedAccount(0L);

        Transfer transfer = transfer(alice, bob, 1_000L);

        assertEquals(TransferStatus.REJECTED, transfer.status());
        assertEquals("INSUFFICIENT_FUNDS", transfer.rejectionReason());
        assertEquals(500L, balance(alice));
        assertEquals(0L, balance(bob));
    }

    @Test
    void postTransfer_replaysSameIdempotencyKeyWithoutMovingMoneyTwice() {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(0L);
        TransferCommand request = request(alice, bob, 1_000L);

        Transfer first = transferService.postTransfer(request);
        Transfer replay = transferService.postTransfer(request);

        assertEquals(first, replay);
        assertEquals(9_000L, balance(alice));
        assertEquals(1_000L, balance(bob));
    }

    @Test
    void postTransfer_replaysRejectionEvenAfterFundsArrive() {
        Account alice = fundedAccount(0L);
        Account bob = fundedAccount(0L);
        TransferCommand request = request(alice, bob, 1_000L);

        Transfer rejected = transferService.postTransfer(request);
        fund(alice, 5_000L);
        Transfer replay = transferService.postTransfer(request);

        assertEquals(TransferStatus.REJECTED, replay.status());
        assertEquals(rejected, replay);
        assertEquals(5_000L, balance(alice));
    }

    @Test
    void postTransfer_rejectsIdempotencyKeyReusedForDifferentTransfer() {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(0L);
        String key = UUID.randomUUID().toString();

        transferService.postTransfer(new TransferCommand(alice.id(), bob.id(), key, 1_000L));

        assertThrows(IdempotencyKeyReuseException.class,
                () -> transferService.postTransfer(new TransferCommand(alice.id(), bob.id(), key, 2_000L)));
        assertEquals(9_000L, balance(alice));
    }

    @Test
    void postTransfer_rejectsUnknownAccount() {
        Account alice = fundedAccount(10_000L);

        assertThrows(AccountNotFoundException.class, () -> transferService.postTransfer(
                new TransferCommand(alice.id(), UUID.randomUUID(), UUID.randomUUID().toString(), 1_000L)));
        assertEquals(10_000L, balance(alice));
    }

    @Test
    void concurrentTransfers_neverOverdrawTheSource() throws Exception {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(0L);

        List<Transfer> results = runConcurrently(20, i -> transfer(alice, bob, 1_000L));

        long posted = results.stream().filter(t -> t.status() == TransferStatus.POSTED).count();
        assertEquals(10, posted);
        assertEquals(0L, balance(alice));
        assertEquals(10_000L, balance(bob));
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKey_postExactlyOnce() throws Exception {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(0L);
        TransferCommand request = request(alice, bob, 1_000L);

        List<Transfer> results = runConcurrently(10, i -> transferService.postTransfer(request));

        assertTrue(results.stream().allMatch(t -> t.equals(results.getFirst())));
        assertEquals(9_000L, balance(alice));
        assertEquals(1_000L, balance(bob));
    }

    @Test
    void concurrentTransfersInOppositeDirections_doNotDeadlock() throws Exception {
        Account alice = fundedAccount(10_000L);
        Account bob = fundedAccount(10_000L);

        List<Transfer> results = runConcurrently(20,
                i -> i % 2 == 0 ? transfer(alice, bob, 100L) : transfer(bob, alice, 100L));

        assertTrue(results.stream().allMatch(t -> t.status() == TransferStatus.POSTED));
        assertEquals(10_000L, balance(alice));
        assertEquals(10_000L, balance(bob));
    }

    private Account fundedAccount(long amountMinor) {
        Account account = accountService.openAccount("acct-" + UUID.randomUUID());
        if (amountMinor > 0)
            fund(account, amountMinor);
        return account;
    }

    private void fund(Account account, long amountMinor) {
        transferService.postTransfer(
                new TransferCommand(SYSTEM_ACCOUNT_ID, account.id(), UUID.randomUUID().toString(), amountMinor));
    }

    private Transfer transfer(Account from, Account to, long amountMinor) {
        return transferService.postTransfer(request(from, to, amountMinor));
    }

    private static TransferCommand request(Account from, Account to, long amountMinor) {
        return new TransferCommand(from.id(), to.id(), UUID.randomUUID().toString(), amountMinor);
    }

    private long balance(Account account) {
        return accountService.getBalance(id(account));
    }

    private static String id(Account account) {
        return account.id().toString();
    }

    private static <T> List<T> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.apply(n);
                }));
            }
            startGate.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures)
                results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
