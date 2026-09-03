package com.heysaz.erp.reporting.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.reporting.api.ReportingService;

@RestController
@RequestMapping("/api/v1/reports")
class ReportingController {

    private final ReportingService reportingService;

    ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/sales-summary")
    @PreAuthorize("hasAuthority('reports.read')")
    ReportingService.SalesSummary salesSummary(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportingService.salesSummary(shopId, from, to);
    }

    @GetMapping("/sales-by-day")
    @PreAuthorize("hasAuthority('reports.read')")
    List<ReportingService.SalesByDayRow> salesByDay(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportingService.salesByDay(shopId, from, to);
    }

    @GetMapping("/top-products")
    @PreAuthorize("hasAuthority('reports.read')")
    List<ReportingService.TopProductRow> topProducts(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return reportingService.topProducts(shopId, from, to, limit);
    }

    @GetMapping("/payment-mix")
    @PreAuthorize("hasAuthority('reports.read')")
    List<ReportingService.PaymentMixRow> paymentMix(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportingService.paymentMix(shopId, from, to);
    }

    @GetMapping("/inventory-valuation")
    @PreAuthorize("hasAuthority('reports.read')")
    List<ReportingService.InventoryValuationRow> inventoryValuation(
            @RequestParam(required = false) UUID shopId) {
        return reportingService.inventoryValuation(shopId);
    }

    @GetMapping("/sales-by-day.csv")
    @PreAuthorize("hasAuthority('reports.read')")
    ResponseEntity<String> salesByDayCsv(
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reportingService.exportSalesByDayCsv(shopId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sales-by-day.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
