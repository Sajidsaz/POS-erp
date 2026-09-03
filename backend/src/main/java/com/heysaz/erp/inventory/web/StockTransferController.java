package com.heysaz.erp.inventory.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.inventory.api.StockTransferService;
import com.heysaz.erp.inventory.api.TransferStatus;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/stock-transfers")
class StockTransferController {

    private final StockTransferService transferService;

    StockTransferController(StockTransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('stock.transfers.write')")
    StockTransferService.StockTransferView createTransfer(
            @Valid @RequestBody StockTransferService.CreateTransferCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return transferService.createTransfer(command, idempotencyKey);
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('stock.transfers.write')")
    StockTransferService.StockTransferView dispatchTransfer(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return transferService.dispatchTransfer(id, idempotencyKey);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('stock.transfers.write')")
    StockTransferService.StockTransferView receiveTransfer(
            @PathVariable UUID id,
            @Valid @RequestBody StockTransferService.ReceiveTransferCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return transferService.receiveTransfer(id, command, idempotencyKey);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('stock.transfers.write')")
    void cancelTransfer(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        transferService.cancelTransfer(id, body != null ? body.get("reason") : null);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('stock.transfers.read')")
    StockTransferService.StockTransferView getTransfer(@PathVariable UUID id) {
        return transferService.getTransfer(id);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('stock.transfers.read')")
    List<StockTransferService.StockTransferView> listTransfers(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) TransferStatus status) {
        return transferService.listTransfers(shopId, status);
    }
}
