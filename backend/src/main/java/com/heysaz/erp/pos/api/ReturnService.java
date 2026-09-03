package com.heysaz.erp.pos.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public interface ReturnService {

    record ReturnLineInput(
            UUID originalSaleLineId,
            @NotNull UUID variantId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @NotNull ReturnCondition condition,
            Money refundAmount) {
    }

    record RefundInput(
            @NotNull PaymentMethod paymentMethod,
            @NotNull Money amount) {
    }

    record ProcessReturnCommand(
            UUID originalSaleId,
            @NotNull UUID shopId,
            @NotNull UUID terminalId,
            UUID shiftId,
            @NotBlank String reason,
            @NotEmpty @Valid List<ReturnLineInput> lines,
            @NotEmpty @Valid List<RefundInput> refunds) {
    }

    record SaleReturnLineView(
            UUID id,
            UUID originalSaleLineId,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal quantity,
            Money refundAmount,
            ReturnCondition condition,
            boolean restocked) {
    }

    record SaleReturnRefundView(
            UUID id,
            PaymentMethod paymentMethod,
            Money amount) {
    }

    record SaleReturnView(
            UUID id,
            String returnNumber,
            UUID originalSaleId,
            UUID shopId,
            UUID terminalId,
            UUID shiftId,
            UUID cashierUserId,
            UUID approvedBy,
            Money refundTotal,
            String reason,
            Instant createdAt,
            List<SaleReturnLineView> lines,
            List<SaleReturnRefundView> refunds) {
    }

    record ProcessExchangeCommand(
            @NotNull @Valid ProcessReturnCommand returnPart,
            @NotNull @Valid PosService.CheckoutCommand salePart) {
    }

    record ExchangeResultView(
            SaleReturnView returnRecord,
            PosService.SaleView saleRecord,
            Money netSettlementAmount) {
    }

    SaleReturnView processReturn(ProcessReturnCommand command, String idempotencyKey);

    ExchangeResultView processExchange(ProcessExchangeCommand command, String idempotencyKey);

    SaleReturnView getReturn(UUID returnId);

    List<SaleReturnView> listReturns(UUID shopId);
}
