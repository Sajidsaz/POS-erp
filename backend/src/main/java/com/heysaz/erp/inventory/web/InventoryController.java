package com.heysaz.erp.inventory.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.inventory.api.InventoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController {

    private final InventoryService inventoryService;

    InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/balances")
    @PreAuthorize("hasAuthority('products.read')")
    List<InventoryService.StockBalanceView> listBalances(
            @RequestParam UUID shopId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean lowStockOnly) {
        return inventoryService.listStockBalances(shopId, categoryId, q, lowStockOnly);
    }

    @GetMapping("/balances/{variantId}")
    @PreAuthorize("hasAuthority('products.read')")
    InventoryService.StockBalanceView getBalance(
            @RequestParam UUID shopId,
            @PathVariable UUID variantId) {
        return inventoryService.getStockBalance(shopId, variantId);
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('products.read')")
    List<InventoryService.StockMovementView> listMovements(
            @RequestParam UUID shopId,
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return inventoryService.listStockMovements(shopId, variantId, limit);
    }

    @GetMapping("/reconstruct")
    @PreAuthorize("hasAuthority('products.read')")
    BigDecimal reconstructBalance(
            @RequestParam UUID shopId,
            @RequestParam UUID variantId) {
        return inventoryService.reconstructBalance(shopId, variantId);
    }

    @GetMapping("/low-stock-alerts")
    @PreAuthorize("hasAuthority('products.read')")
    List<InventoryService.LowStockAlertView> getLowStockAlerts(@RequestParam UUID shopId) {
        return inventoryService.getLowStockAlerts(shopId);
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.adjustments.write')")
    InventoryService.StockAdjustmentView adjustStock(
            @Valid @RequestBody InventoryService.AdjustStockCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return inventoryService.adjustStock(command, idempotencyKey);
    }

    @PutMapping("/thresholds/{variantId}")
    @PreAuthorize("hasAuthority('products.write')")
    void setThresholds(
            @RequestParam UUID shopId,
            @PathVariable UUID variantId,
            @Valid @RequestBody InventoryService.SetThresholdsCommand command) {
        inventoryService.setThresholds(shopId, variantId, command);
    }
}
