package com.akash.ledger.service;

import com.akash.ledger.exception.AccountNotFoundException;
import com.akash.ledger.exception.IdempotencyKeyReuseException;
import com.akash.ledger.exception.SelfTransferException;
import com.akash.ledger.model.Transfer;
import com.akash.ledger.model.TransferCommand;
import com.akash.ledger.model.TransferStatus;
import com.akash.ledger.repository.BalanceRepository;
import com.akash.ledger.repository.BalanceRepository.LockedBalance;
import com.akash.ledger.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private final TransferRepository transferRepository;
    private final BalanceRepository balanceRepository;

    public TransferService(TransferRepository transferRepository, BalanceRepository balanceRepository) {
        this.transferRepository = transferRepository;
        this.balanceRepository = balanceRepository;
    }

    @Transactional
    public Transfer postTransfer(TransferCommand command) {
        UUID fromId = command.fromAccountId();
        UUID toId = command.toAccountId();
        long amount = command.amountMinor();

        // self transfer check
        if (fromId.equals(toId))
            throw new SelfTransferException("Source and destination account cannot be the same");

        // Lock rows so that enough balance exist for transfer
        Map<UUID, LockedBalance> balances = balanceRepository.lockForUpdate(fromId, toId);
        requireAccount(balances, fromId);
        requireAccount(balances, toId);

        LockedBalance source = balances.get(fromId);
        boolean canDebit = source.systemAccount() || source.balanceMinor() >= amount;
        TransferStatus status = canDebit ? TransferStatus.POSTED : TransferStatus.REJECTED;

        Optional<Transfer> inserted = transferRepository.insertIfAbsent(
                command.idempotencyKey(), fromId, toId, amount, status, canDebit ? null : INSUFFICIENT_FUNDS);
        if (inserted.isEmpty())
            return replay(command.idempotencyKey(), fromId, toId, amount);

        Transfer transfer = inserted.get();
        if (transfer.status() == TransferStatus.POSTED) {
            balanceRepository.adjust(fromId, -amount);
            balanceRepository.adjust(toId, amount);
        }
        return transfer;
    }

    private Transfer replay(String idempotencyKey, UUID fromId, UUID toId, long amount) {
        Transfer original = transferRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key conflicted but no transfer found: " + idempotencyKey));

        boolean samePayload = original.fromAccountId().equals(fromId)
                && original.toAccountId().equals(toId)
                && original.amountMinor() == amount;
        if (!samePayload)
            throw new IdempotencyKeyReuseException("Idempotency key " + idempotencyKey + " was already used for a different transfer");

        return original;
    }

    private static void requireAccount(Map<UUID, LockedBalance> balances, UUID accountId) {
        if (!balances.containsKey(accountId))
            throw new AccountNotFoundException("Account not found: " + accountId);
    }
}
