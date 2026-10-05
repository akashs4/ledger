package com.akash.ledger.rest;

import com.akash.ledger.rest.dto.request.AccountRequestDto;
import com.akash.ledger.rest.dto.response.AccountResponseDto;
import com.akash.ledger.rest.dto.response.AccountStatementResponseDto;
import com.akash.ledger.rest.dto.response.AccountsVerificationResponseDto;
import com.akash.ledger.rest.dto.response.BalanceRebuildResponseDto;
import com.akash.ledger.rest.mapper.DtoMapper;
import com.akash.ledger.service.AccountService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/accounts")
public class AccountResource {

    private static final int DEFAULT_STATEMENT_DAYS = 30;

    private final AccountService accountService;

    public AccountResource(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public AccountResponseDto openAccount(@Valid @RequestBody AccountRequestDto accountDto) {
        log.info("Opening account");
        return DtoMapper.toAccountResponse(accountService.openAccount(accountDto.name()));
    }

    @GetMapping
    public List<AccountResponseDto> findAll() {
        return DtoMapper.convertList(accountService.findAll(), DtoMapper::toAccountResponse);
    }

    @GetMapping(path = "/{id}/balance")
    public long getBalance(@PathVariable String id) {
        log.info("Getting balance for id: {}", id);
        return accountService.getBalance(id);
    }

    @GetMapping(path = "/{id}")
    public AccountResponseDto getById(@PathVariable String id) {
        log.info("Fetching account details for : {}", id);
        return DtoMapper.toAccountResponse(accountService.getById(id));
    }

    @GetMapping(path = "/balances/verify")
    public AccountsVerificationResponseDto verifyBalances() {
        log.info("Verifying all balances");
        return DtoMapper.toVerificationResponse(accountService.verifyBalances());
    }

    @PostMapping(path = "/balances/rebuild")
    public BalanceRebuildResponseDto rebuildBalances() {
        log.info("Rebuilding all balances from transfers");
        return DtoMapper.toRebuildResponse(accountService.rebuildBalances());
    }

    @GetMapping(path = "/{id}/statement")
    public AccountStatementResponseDto getStatement(
            @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("Building statement for account: {}", id);
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = from != null ? from : end.minusDays(DEFAULT_STATEMENT_DAYS);
        return DtoMapper.toStatementResponse(accountService.getStatement(id, start, end, cursor, limit));
    }
}
