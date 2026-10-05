package com.akash.ledger.rest;

import com.akash.ledger.model.Transfer;
import com.akash.ledger.model.TransferStatus;
import com.akash.ledger.rest.dto.request.TransferRequestDto;
import com.akash.ledger.rest.dto.response.TransferResponseDto;
import com.akash.ledger.rest.mapper.DtoMapper;
import com.akash.ledger.service.TransferService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/transfers")
public class TransferResource {
    private final TransferService transferService;

    public TransferResource(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponseDto> createTransfer(@Valid @RequestBody TransferRequestDto newTransferRequest) {
        log.info("Creating transfer");
        Transfer transfer = transferService.postTransfer(DtoMapper.toTransferCommand(newTransferRequest));
        HttpStatus status = transfer.status() == TransferStatus.POSTED ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(DtoMapper.toTransferResponse(transfer));
    }
}
