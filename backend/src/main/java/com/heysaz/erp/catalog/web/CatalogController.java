package com.heysaz.erp.catalog.web;

import java.time.LocalDate;
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

import com.heysaz.erp.catalog.api.CatalogService;
import com.heysaz.erp.catalog.api.PricingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
class CatalogController {

    private final CatalogService catalog;
    private final PricingService pricing;

    CatalogController(CatalogService catalog, PricingService pricing) {
        this.catalog = catalog;
        this.pricing = pricing;
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('products.write')")
    CatalogService.ProductView createProduct(
            @Valid @RequestBody CatalogService.CreateProductCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return catalog.createProduct(command, idempotencyKey);
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('products.read')")
    CatalogService.ProductView getProduct(@PathVariable UUID id) {
        return catalog.getProduct(id);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('products.read')")
    List<CatalogService.ProductView> searchProducts(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return catalog.searchProducts(q, limit);
    }

    /** The till's scan lookup. NFR-P-002 budgets this at p95 ≤ 300 ms. */
    @GetMapping("/products/by-barcode/{barcode}")
    @PreAuthorize("hasAuthority('products.read')")
    CatalogService.VariantView byBarcode(@PathVariable String barcode) {
        return catalog.findByBarcode(barcode);
    }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('products.write')")
    CatalogService.UnitView createUnit(@Valid @RequestBody CatalogService.CreateUnitCommand command) {
        return catalog.createUnit(command);
    }

    @GetMapping("/units")
    @PreAuthorize("hasAuthority('products.read')")
    List<CatalogService.UnitView> listUnits() {
        return catalog.listUnits();
    }

    @PostMapping("/products/{id}/unit-conversions")
    @PreAuthorize("hasAuthority('products.write')")
    void addUnitConversion(@PathVariable UUID id,
                           @Valid @RequestBody CatalogService.AddUnitConversionCommand command) {
        catalog.addUnitConversion(id, command);
    }

    // ------------------------------------------------------------ tax and price

    @PostMapping("/tax-classes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('settings.write')")
    PricingService.TaxClassView createTaxClass(
            @Valid @RequestBody PricingService.CreateTaxClassCommand command) {
        return pricing.createTaxClass(command);
    }

    @PostMapping("/tax-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('settings.write')")
    PricingService.TaxRateView setTaxRate(
            @Valid @RequestBody PricingService.SetTaxRateCommand command) {
        return pricing.setTaxRate(command);
    }

    @PostMapping("/price-lists")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('products.write')")
    PricingService.PriceListView createPriceList(
            @Valid @RequestBody PricingService.CreatePriceListCommand command) {
        return pricing.createPriceList(command);
    }

    @PostMapping("/prices")
    @PreAuthorize("hasAuthority('products.write')")
    void setPrice(@Valid @RequestBody PricingService.SetPriceCommand command) {
        pricing.setPrice(command);
    }

    @GetMapping("/prices/resolve")
    @PreAuthorize("hasAuthority('products.read')")
    PricingService.ResolvedPrice resolvePrice(@RequestParam UUID variantId,
                                              @RequestParam(required = false) UUID shopId,
                                              @RequestParam(required = false) LocalDate asAt) {
        return pricing.resolve(variantId, shopId, asAt);
    }
}
