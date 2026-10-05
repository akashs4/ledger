package com.akash.ledger.rest.mapper;

import com.akash.ledger.model.Account;
import com.akash.ledger.model.BalanceMismatch;
import com.akash.ledger.model.BalanceRebuild;
import com.akash.ledger.model.BalanceVerification;
import com.akash.ledger.model.Statement;
import com.akash.ledger.model.StatementEntry;
import com.akash.ledger.model.StatementLine;
import com.akash.ledger.model.Transfer;
import com.akash.ledger.model.TransferCommand;
import com.akash.ledger.rest.dto.request.TransferRequestDto;
import com.akash.ledger.rest.dto.response.AccountMismatch;
import com.akash.ledger.rest.dto.response.AccountResponseDto;
import com.akash.ledger.rest.dto.response.AccountStatementEntryDto;
import com.akash.ledger.rest.dto.response.AccountStatementResponseDto;
import com.akash.ledger.rest.dto.response.AccountsVerificationResponseDto;
import com.akash.ledger.rest.dto.response.BalanceRebuildResponseDto;
import com.akash.ledger.rest.dto.response.TransferResponseDto;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public class DtoMapper {
    public static AccountResponseDto toAccountResponse(Account account) {
        return new AccountResponseDto(account.id(), account.name());
    }

    public static TransferCommand toTransferCommand(TransferRequestDto request) {
        return new TransferCommand(
                UUID.fromString(request.fromId()),
                UUID.fromString(request.toId()),
                request.requestId(),
                request.amountMinor()
        );
    }

    public static TransferResponseDto toTransferResponse(Transfer transfer) {
        return new TransferResponseDto(
                transfer.id().toString(),
                transfer.idempotencyKey(),
                transfer.fromAccountId().toString(),
                transfer.toAccountId().toString(),
                transfer.amountMinor(),
                transfer.status().name(),
                transfer.rejectionReason()
        );
    }

    public static AccountsVerificationResponseDto toVerificationResponse(BalanceVerification verification) {
        return new AccountsVerificationResponseDto(
                verification.consistent(),
                verification.accountsChecked(),
                verification.mismatchCount(),
                verification.ledgerNetMinor(),
                convertList(verification.mismatches(), DtoMapper::toAccountMismatch)
        );
    }

    public static BalanceRebuildResponseDto toRebuildResponse(BalanceRebuild rebuild) {
        return new BalanceRebuildResponseDto(
                rebuild.accountsRebuilt(),
                rebuild.accountsCorrected(),
                convertList(rebuild.corrections(), DtoMapper::toAccountMismatch),
                rebuild.consistentAfterRebuild()
        );
    }

    public static AccountStatementResponseDto toStatementResponse(Statement statement) {
        return new AccountStatementResponseDto(
                statement.accountId().toString(),
                statement.from(),
                statement.to(),
                statement.openingBalanceMinor(),
                statement.closingBalanceMinor(),
                convertList(statement.entries(), DtoMapper::toStatementEntry),
                statement.nextCursor()
        );
    }

    public static <T, O> List<O> convertList(List<T> input, Function<T, O> mapper) {
        return input.stream()
                .map(mapper)
                .toList();
    }

    private static AccountMismatch toAccountMismatch(BalanceMismatch mismatch) {
        Long cached = mismatch.cachedBalanceMinor();
        return new AccountMismatch(
                mismatch.accountId().toString(),
                cached,
                mismatch.derivedBalanceMinor(),
                cached == null ? null : cached - mismatch.derivedBalanceMinor()
        );
    }

    private static AccountStatementEntryDto toStatementEntry(StatementEntry entry) {
        StatementLine line = entry.line();
        boolean credit = line.amountMinor() > 0;
        String description = (credit ? "Transfer from " : "Transfer to ") + line.counterpartyName();
        return new AccountStatementEntryDto(
                line.transferId().toString(),
                line.createdAt().withOffsetSameInstant(ZoneOffset.UTC),
                description,
                line.counterpartyId().toString(),
                Math.abs(line.amountMinor()),
                credit,
                entry.runningBalanceMinor()
        );
    }
}
