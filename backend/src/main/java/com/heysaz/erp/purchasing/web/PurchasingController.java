package com.heysaz.erp.purchasing.web;

import java.util.List;
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

import com.heysaz.erp.purchasing.api.PurchaseOrderStatus;
import com.heysaz.erp.purchasing.api.PurchasingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/purchasing")
class PurchasingController {

    private final PurchasingService purchasingService;

    PurchasingController(PurchasingService purchasingService) {
        this.purchasingService = purchasingService;
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('purchasing.write')")
    PurchasingService.SupplierView createSupplier(
            @Valid @RequestBody PurchasingService.CreateSupplierCommand command) {
        return purchasingService.createSupplier(command);
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAuthority('purchasing.read')")
    List<PurchasingService.SupplierView> listSuppliers() {
        return purchasingService.listSuppliers();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('purchasing.write')")
    PurchasingService.PurchaseOrderView createOrder(
            @Valid @RequestBody PurchasingService.CreatePurchaseOrderCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return purchasingService.createPurchaseOrder(command, idempotencyKey);
    }

    @PostMapping("/orders/{orderId}/approve")
    @PreAuthorize("hasAuthority('purchasing.write')")
    PurchasingService.PurchaseOrderView approveOrder(
            @PathVariable UUID orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return purchasingService.approvePurchaseOrder(orderId, idempotencyKey);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('purchasing.write')")
    void cancelOrder(@PathVariable UUID orderId, @RequestParam(required = false) String reason) {
        purchasingService.cancelPurchaseOrder(orderId, reason);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('purchasing.read')")
    PurchasingService.PurchaseOrderView getOrder(@PathVariable UUID orderId) {
        return purchasingService.getPurchaseOrder(orderId);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('purchasing.read')")
    List<PurchasingService.PurchaseOrderView> listOrders(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) PurchaseOrderStatus status) {
        return purchasingService.listPurchaseOrders(shopId, status);
    }

    @PostMapping("/goods-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('purchasing.write')")
    PurchasingService.GoodsReceiptView receiveGoods(
            @Valid @RequestBody PurchasingService.ReceiveGoodsCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return purchasingService.receiveGoods(command, idempotencyKey);
    }

    @GetMapping("/goods-receipts/{receiptId}")
    @PreAuthorize("hasAuthority('purchasing.read')")
    PurchasingService.GoodsReceiptView getGoodsReceipt(@PathVariable UUID receiptId) {
        return purchasingService.getGoodsReceipt(receiptId);
    }

    @GetMapping("/goods-receipts")
    @PreAuthorize("hasAuthority('purchasing.read')")
    List<PurchasingService.GoodsReceiptView> listGoodsReceipts(@RequestParam UUID shopId) {
        return purchasingService.listGoodsReceipts(shopId);
    }
}
