package com.akash.ledger.repository.mapper;

import com.akash.ledger.jooq.tables.records.AccountsRecord;
import com.akash.ledger.jooq.tables.records.TransfersRecord;
import com.akash.ledger.model.Account;
import com.akash.ledger.model.Transfer;
import com.akash.ledger.model.TransferStatus;

public class RecordToModelMapper {
    public static Account toAccount(AccountsRecord record) {
        return new Account(record.getId(), record.getName());
    }

    public static Transfer toTransfer(TransfersRecord record) {
        return new Transfer(
                record.getId(),
                record.getIdempotencyKey(),
                record.getFromAccountId(),
                record.getToAccountId(),
                record.getAmountMinor(),
                TransferStatus.valueOf(record.getStatus()),
                record.getRejectionReason()
        );
    }
}
