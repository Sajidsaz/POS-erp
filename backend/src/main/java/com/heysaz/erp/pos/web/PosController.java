package com.heysaz.erp.pos.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.pos.api.PosService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/pos")
class PosController {

    private final PosService posService;

    PosController(PosService posService) {
        this.posService = posService;
    }

    @PostMapping("/carts/calculate")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    PosService.CalculatedCartView calculate(@Valid @RequestBody PosService.CalculateCartCommand command) {
        return posService.calculateCart(command);
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('pos.sales.write')")
    PosService.SaleView checkout(
            @Valid @RequestBody PosService.CheckoutCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return posService.checkout(command, idempotencyKey);
    }

    @GetMapping("/sales/{saleId}")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    PosService.SaleView getSale(@PathVariable UUID saleId) {
        return posService.getSale(saleId);
    }

    @GetMapping("/sales/by-invoice/{invoiceNumber}")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    PosService.SaleView getByInvoice(@PathVariable String invoiceNumber) {
        return posService.getSaleByInvoiceNumber(invoiceNumber);
    }

    @GetMapping("/sales/{saleId}/receipt")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    PosService.ReceiptView getReceipt(@PathVariable UUID saleId) {
        return posService.getReceipt(saleId);
    }

    @PostMapping("/sales/{saleId}/receipt/reprint")
    @PreAuthorize("hasAuthority('pos.sales.write')")
    PosService.ReceiptView reprintReceipt(
            @PathVariable UUID saleId,
            @RequestParam(required = false) String reason) {
        return posService.reprintReceipt(saleId, reason);
    }

    @PostMapping("/held-carts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('pos.sales.write')")
    PosService.HeldCartView holdCart(
            @Valid @RequestBody PosService.HoldCartCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return posService.holdCart(command, idempotencyKey);
    }

    @GetMapping("/held-carts")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    List<PosService.HeldCartView> listHeldCarts(@RequestParam UUID shopId) {
        return posService.listHeldCarts(shopId);
    }

    @GetMapping("/held-carts/{cartId}")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    PosService.HeldCartView getHeldCart(@PathVariable UUID cartId) {
        return posService.getHeldCart(cartId);
    }

    @DeleteMapping("/held-carts/{cartId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('pos.sales.write')")
    void deleteHeldCart(@PathVariable UUID cartId) {
        posService.deleteHeldCart(cartId);
    }
}
