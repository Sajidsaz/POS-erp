package com.heysaz.erp.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Products, variants, barcodes and units.
 *
 * <p>FR-CAT-008/009/010 set the shape: the <em>variant</em> is the stock-keeping entity.
 * A product created without variants still gets exactly one, flagged as the default, so
 * that stock, sale lines, purchase lines and counts always reference a variant and no code
 * path downstream has to branch on "does this product have variants?".
 */
public interface CatalogService {

    record CreateProductCommand(
            @NotBlank @Size(max = 64) String sku,
            @NotBlank @Size(max = 200) String name,
            String description,
            UUID categoryId,
            @NotNull UUID stockingUnitId,
            UUID taxClassId,
            /** FR-CAT-004: false for a service or other non-stock item. */
            Boolean stocked,
            Money cost,
            Money price,
            List<String> barcodes,
            /** Empty for a simple product; one entry per variant otherwise. */
            @Valid List<VariantSpec> variants) {
    }

    record VariantSpec(
            @NotBlank @Size(max = 64) String sku,
            String name,
            /** e.g. {"Size": "M", "Colour": "Red"} */
            Map<String, String> axisValues,
            Money cost,
            Money price,
            List<String> barcodes) {
    }

    record VariantView(UUID id, String sku, String name, Map<String, String> axisValues,
                       Money price, Money averageCost, boolean isDefault, boolean active,
                       List<String> barcodes) {
    }

    record ProductView(UUID id, String sku, String name, UUID categoryId, UUID stockingUnitId,
                       UUID taxClassId, boolean stocked, boolean hasVariants, String status,
                       List<VariantView> variants) {
    }

    record CreateUnitCommand(
            @NotBlank @Size(max = 16) String code,
            @NotBlank @Size(max = 60) String name,
            Boolean integral) {
    }

    record UnitView(UUID id, String code, String name, boolean integral) {
    }

    /**
     * FR-CAT-011. {@code factorToStocking} is how many stocking units one of this unit
     * contains — a CASE of 24 EACH has factor 24.
     */
    record AddUnitConversionCommand(
            @NotNull UUID unitId,
            @NotNull java.math.BigDecimal factorToStocking,
            Boolean purchasable,
            Boolean sellable) {
    }

    ProductView createProduct(CreateProductCommand command, String idempotencyKey);

    ProductView getProduct(UUID id);

    List<ProductView> searchProducts(String query, int limit);

    /** The till's lookup. Returns the variant a scanned barcode resolves to. */
    VariantView findByBarcode(String barcode);

    UnitView createUnit(CreateUnitCommand command);

    List<UnitView> listUnits();

    void addUnitConversion(UUID productId, AddUnitConversionCommand command);

    /**
     * Converts a quantity entered in some unit into the product's stocking unit.
     * FR-CAT-011 requires this to happen before any stock movement is written.
     */
    java.math.BigDecimal toStockingUnits(UUID productId, UUID unitId, java.math.BigDecimal quantity);
}
