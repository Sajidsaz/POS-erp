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

import com.heysaz.erp.inventory.api.StockCountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/stock-counts")
class StockCountController {

    private final StockCountService countService;

    StockCountController(StockCountService countService) {
        this.countService = countService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.adjustments.write')")
    StockCountService.StockCountView createCount(
            @Valid @RequestBody StockCountService.CreateStockCountCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return countService.createCount(command, idempotencyKey);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('inventory.adjustments.write')")
    StockCountService.StockCountView recordCounts(
            @PathVariable UUID id,
            @Valid @RequestBody StockCountService.RecordCountsCommand command) {
        return countService.recordCounts(id, command);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('inventory.adjustments.approve') or hasAuthority('inventory.adjustments.write')")
    StockCountService.StockCountView approveCount(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return countService.approveCount(id, idempotencyKey);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('inventory.adjustments.write')")
    void cancelCount(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        countService.cancelCount(id, body != null ? body.get("reason") : null);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.adjustments.read')")
    StockCountService.StockCountView getCount(@PathVariable UUID id) {
        return countService.getCount(id);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.adjustments.read')")
    List<StockCountService.StockCountView> listCounts(@RequestParam(required = false) UUID shopId) {
        return countService.listCounts(shopId);
    }
}
