package com.heysaz.erp.pos.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public interface PosService {

    record CartLineInput(
            @NotNull UUID variantId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            Money unitPrice,
            Money discountAmount) {
    }

    record CalculateCartCommand(
            @NotNull UUID shopId,
            @NotEmpty @Valid List<CartLineInput> lines,
            PaymentMethod primaryPaymentMethod) {
    }

    record CalculatedLineView(
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal quantity,
            Money unitPrice,
            Money discountAmount,
            BigDecimal taxRate,
            Money taxAmount,
            Money lineTotal) {
    }

    record CalculatedCartView(
            List<CalculatedLineView> lines,
            Money subtotal,
            Money discountTotal,
            Money taxTotal,
            Money grandTotal,
            Money cashRounding,
            Money payableTotal) {
    }

    record PaymentInput(
            @NotNull PaymentMethod method,
            @NotNull Money amount,
            String reference,
            Money tenderedAmount) {
    }

    record CheckoutCommand(
            @NotNull UUID shopId,
            @NotNull UUID terminalId,
            UUID shiftId,
            @NotEmpty @Valid List<CartLineInput> lines,
            @NotEmpty @Valid List<PaymentInput> payments,
            UUID customerId,
            String notes) {
    }

    record SaleLineView(
            UUID id,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal quantity,
            Money unitPrice,
            Money discountAmount,
            BigDecimal taxRate,
            Money taxAmount,
            Money lineTotal,
            Money costSnapshot,
            BigDecimal returnedQuantity) {
    }

    record SalePaymentView(
            UUID id,
            PaymentMethod paymentMethod,
            Money amount,
            String reference,
            Money tenderedAmount,
            Money changeAmount) {
    }

    record SaleView(
            UUID id,
            String invoiceNumber,
            UUID shopId,
            UUID terminalId,
            UUID shiftId,
            UUID cashierUserId,
            String status,
            Money subtotal,
            Money discountTotal,
            Money taxTotal,
            Money grandTotal,
            Money cashRounding,
            Money totalTendered,
            Money changeGiven,
            UUID customerId,
            String notes,
            Instant createdAt,
            List<SaleLineView> lines,
            List<SalePaymentView> payments) {
    }

    record HoldCartCommand(
            @NotNull UUID shopId,
            @NotNull UUID terminalId,
            String reference,
            @NotEmpty @Valid List<CartLineInput> lines) {
    }

    record HeldCartView(
            UUID id,
            UUID shopId,
            UUID terminalId,
            UUID cashierUserId,
            String reference,
            List<CartLineInput> lines,
            Instant createdAt) {
    }

    record ReceiptTaxLine(
            BigDecimal rate,
            Money taxableAmount,
            Money taxAmount) {
    }

    record ReceiptPaymentLine(
            PaymentMethod method,
            Money amount,
            String reference) {
    }

    record ReceiptLine(
            String description,
            BigDecimal quantity,
            Money unitPrice,
            Money discountAmount,
            Money lineTotal) {
    }

    record ReceiptView(
            UUID saleId,
            String invoiceNumber,
            String shopName,
            String shopAddress,
            String taxRegistrationNo,
            Instant serverTime,
            String cashierName,
            String terminalCode,
            List<ReceiptLine> lines,
            Money subtotal,
            Money discountTotal,
            List<ReceiptTaxLine> taxBreakdown,
            Money grandTotal,
            Money cashRounding,
            List<ReceiptPaymentLine> payments,
            Money tenderedAmount,
            Money changeGiven,
            String receiptHeader,
            String receiptFooter,
            boolean isReprint) {
    }

    CalculatedCartView calculateCart(CalculateCartCommand command);

    SaleView checkout(CheckoutCommand command, String idempotencyKey);

    SaleView getSale(UUID saleId);

    SaleView getSaleByInvoiceNumber(String invoiceNumber);

    HeldCartView holdCart(HoldCartCommand command, String idempotencyKey);

    HeldCartView getHeldCart(UUID cartId);

    List<HeldCartView> listHeldCarts(UUID shopId);

    void deleteHeldCart(UUID cartId);

    ReceiptView getReceipt(UUID saleId);

    ReceiptView reprintReceipt(UUID saleId, String reason);
}
