package com.heysaz.erp.pos.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import com.heysaz.erp.pos.api.ShiftService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/pos/shifts")
class ShiftController {

    private final ShiftService shiftService;

    ShiftController(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('pos.sales.write')")
    ShiftService.ShiftSummaryView open(
            @Valid @RequestBody ShiftService.OpenShiftCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return shiftService.openShift(command, idempotencyKey);
    }

    @PostMapping("/{shiftId}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('pos.sales.write')")
    ShiftService.ShiftMovementView recordMovement(
            @PathVariable UUID shiftId,
            @Valid @RequestBody ShiftService.RecordShiftMovementCommand command) {
        return shiftService.recordMovement(shiftId, command);
    }

    @GetMapping("/{shiftId}/x-report")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    ShiftService.XReportView xReport(@PathVariable UUID shiftId) {
        return shiftService.getXReport(shiftId);
    }

    @PostMapping("/{shiftId}/close")
    @PreAuthorize("hasAuthority('pos.sales.write')")
    ShiftService.ZReportView close(
            @PathVariable UUID shiftId,
            @Valid @RequestBody ShiftService.CloseShiftCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return shiftService.closeShift(shiftId, command, idempotencyKey);
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    ResponseEntity<ShiftService.ShiftSummaryView> current(@RequestParam UUID terminalId) {
        return shiftService.getCurrentShift(terminalId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{shiftId}")
    @PreAuthorize("hasAuthority('pos.sales.read')")
    ShiftService.ShiftSummaryView get(@PathVariable UUID shiftId) {
        return shiftService.getShift(shiftId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('pos.sales.read')")
    List<ShiftService.ShiftSummaryView> list(@RequestParam UUID shopId) {
        return shiftService.listShifts(shopId);
    }
}
