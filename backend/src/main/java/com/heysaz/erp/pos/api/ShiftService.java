package com.heysaz.erp.pos.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.constraints.NotNull;

public interface ShiftService {

    record OpenShiftCommand(
            @NotNull UUID shopId,
            @NotNull UUID terminalId,
            @NotNull Money openingCash,
            String notes) {
    }

    record RecordShiftMovementCommand(
            @NotNull String movementType, // CASH_IN, CASH_OUT, DRAWER_OPEN, EXPENSE
            @NotNull Money amount,
            @NotNull String reason) {
    }

    record CloseShiftCommand(
            @NotNull Money closingCash,
            String notes) {
    }

    record ShiftMovementView(
            UUID id,
            UUID shiftId,
            String movementType,
            Money amount,
            String reason,
            UUID actorUserId,
            Instant occurredAt) {
    }

    record ShiftSummaryView(
            UUID id,
            UUID shopId,
            UUID terminalId,
            UUID cashierUserId,
            ShiftStatus status,
            Money openingCash,
            Money cashSales,
            Money changeGiven,
            Money cashRefunds,
            Money cashIn,
            Money cashOut,
            Money cashExpenses,
            Money cashRounding,
            Money expectedCash,
            Money closingCash,
            Money cashVariance,
            Instant openedAt,
            Instant closedAt,
            String notes,
            List<ShiftMovementView> movements) {
    }

    record XReportView(
            UUID shiftId,
            String shopName,
            String terminalCode,
            String cashierName,
            Instant openedAt,
            Instant reportTime,
            Money openingCash,
            Money cashSales,
            Money cardSales,
            Money otherSales,
            Money totalSales,
            Money cashRefunds,
            Money changeGiven,
            Money cashIn,
            Money cashOut,
            Money cashExpenses,
            Money cashRounding,
            Money expectedCash,
            long salesCount,
            long returnsCount) {
    }

    record ZReportView(
            UUID shiftId,
            String shopName,
            String terminalCode,
            String cashierName,
            Instant openedAt,
            Instant closedAt,
            Money openingCash,
            Money cashSales,
            Money cardSales,
            Money otherSales,
            Money totalSales,
            Money cashRefunds,
            Money changeGiven,
            Money cashIn,
            Money cashOut,
            Money cashExpenses,
            Money cashRounding,
            Money expectedCash,
            Money countedCash,
            Money cashVariance,
            long salesCount,
            long returnsCount,
            String notes) {
    }

    ShiftSummaryView openShift(OpenShiftCommand command, String idempotencyKey);

    ShiftMovementView recordMovement(UUID shiftId, RecordShiftMovementCommand command);

    XReportView getXReport(UUID shiftId);

    ZReportView closeShift(UUID shiftId, CloseShiftCommand command, String idempotencyKey);

    java.util.Optional<ShiftSummaryView> getCurrentShift(UUID terminalId);

    ShiftSummaryView getShift(UUID shiftId);

    List<ShiftSummaryView> listShifts(UUID shopId);
}
