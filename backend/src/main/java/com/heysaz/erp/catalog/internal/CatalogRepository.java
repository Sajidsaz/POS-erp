package com.heysaz.erp.catalog.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.catalog.api.CatalogService.UnitView;
import com.heysaz.erp.catalog.api.CatalogService.VariantView;
import com.heysaz.erp.platform.money.Money;

@Repository
class CatalogRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    CatalogRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- products

    void insertProduct(UUID id, UUID orgId, String sku, String name, String description,
                       UUID categoryId, UUID stockingUnitId, UUID taxClassId,
                       boolean stocked, boolean hasVariants) {
        jdbc.sql("""
                INSERT INTO app.product
                    (id, org_id, sku, name, description, category_id, stocking_unit_id,
                     tax_class_id, stocked, has_variants)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(sku).param(name).param(description)
                .param(categoryId).param(stockingUnitId).param(taxClassId)
                .param(stocked).param(hasVariants)
                .update();
    }

    boolean skuExists(String sku) {
        return jdbc.sql("SELECT count(*) FROM app.product WHERE sku = ?")
                .param(sku).query(Long.class).single() > 0;
    }

    boolean variantSkuExists(String sku) {
        return jdbc.sql("SELECT count(*) FROM app.variant WHERE sku = ?")
                .param(sku).query(Long.class).single() > 0;
    }

    Optional<ProductRow> findProduct(UUID id) {
        return jdbc.sql("SELECT * FROM app.product WHERE id = ?")
                .param(id).query(this::mapProduct).optional();
    }

    List<ProductRow> searchProducts(String query, int limit) {
        // Matches the ways a person actually looks a product up at a counter: its code,
        // its name, or a barcode they have just scanned into the search box.
        return jdbc.sql("""
                SELECT DISTINCT p.* FROM app.product p
                LEFT JOIN app.variant v ON v.product_id = p.id
                LEFT JOIN app.barcode b ON b.variant_id = v.id
                WHERE p.status = 'ACTIVE'
                  AND (p.sku ILIKE ? OR p.name ILIKE ? OR v.sku ILIKE ? OR b.code = ?)
                ORDER BY p.name
                LIMIT ?
                """)
                .param("%" + query + "%").param("%" + query + "%")
                .param("%" + query + "%").param(query).param(limit)
                .query(this::mapProduct)
                .list();
    }

    // ---------------------------------------------------------------- variants

    void insertVariant(UUID id, UUID orgId, UUID productId, String sku, String name,
                       Map<String, String> axisValues, BigDecimal cost, BigDecimal price,
                       boolean isDefault) {
        jdbc.sql("""
                INSERT INTO app.variant
                    (id, org_id, product_id, sku, name, axis_values, average_cost,
                     base_price, is_default)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, coalesce(?, 0), ?, ?)
                """)
                .param(id).param(orgId).param(productId).param(sku).param(name)
                .param(toJson(axisValues == null ? Map.of() : axisValues))
                .param(cost).param(price).param(isDefault)
                .update();
    }

    List<VariantView> findVariants(UUID productId) {
        List<VariantView> variants = new ArrayList<>();
        for (VariantRow row : jdbc.sql(
                "SELECT * FROM app.variant WHERE product_id = ? ORDER BY is_default DESC, sku")
                .param(productId).query(this::mapVariant).list()) {
            variants.add(toView(row, findBarcodes(row.id())));
        }
        return variants;
    }

    Optional<VariantView> findVariantByBarcode(String barcode) {
        return jdbc.sql("""
                SELECT v.* FROM app.variant v
                JOIN app.barcode b ON b.variant_id = v.id
                WHERE b.code = ? AND v.active
                """)
                .param(barcode)
                .query(this::mapVariant)
                .optional()
                .map(row -> toView(row, findBarcodes(row.id())));
    }

    // ---------------------------------------------------------------- barcodes

    void insertBarcode(UUID orgId, UUID variantId, String code, boolean primary) {
        jdbc.sql("INSERT INTO app.barcode (id, org_id, variant_id, code, is_primary) "
                        + "VALUES (?, ?, ?, ?, ?)")
                .param(UUID.randomUUID()).param(orgId).param(variantId).param(code).param(primary)
                .update();
    }

    boolean barcodeExists(String code) {
        return jdbc.sql("SELECT count(*) FROM app.barcode WHERE code = ?")
                .param(code).query(Long.class).single() > 0;
    }

    List<String> findBarcodes(UUID variantId) {
        return jdbc.sql("SELECT code FROM app.barcode WHERE variant_id = ? ORDER BY is_primary DESC, code")
                .param(variantId).query(String.class).list();
    }

    // ------------------------------------------------------------------- units

    void insertUnit(UUID id, UUID orgId, String code, String name, boolean integral) {
        jdbc.sql("INSERT INTO app.unit (id, org_id, code, name, integral) VALUES (?, ?, ?, ?, ?)")
                .param(id).param(orgId).param(code).param(name).param(integral)
                .update();
    }

    boolean unitCodeExists(String code) {
        return jdbc.sql("SELECT count(*) FROM app.unit WHERE code = ?")
                .param(code).query(Long.class).single() > 0;
    }

    Optional<UnitView> findUnit(UUID id) {
        return jdbc.sql("SELECT * FROM app.unit WHERE id = ?")
                .param(id).query(this::mapUnit).optional();
    }

    List<UnitView> findUnits() {
        return jdbc.sql("SELECT * FROM app.unit ORDER BY code").query(this::mapUnit).list();
    }

    void insertUnitConversion(UUID orgId, UUID productId, UUID unitId, BigDecimal factor,
                              boolean purchasable, boolean sellable) {
        jdbc.sql("""
                INSERT INTO app.unit_conversion
                    (id, org_id, product_id, unit_id, factor_to_stocking, purchasable, sellable)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_id, unit_id)
                DO UPDATE SET factor_to_stocking = EXCLUDED.factor_to_stocking,
                              purchasable = EXCLUDED.purchasable,
                              sellable = EXCLUDED.sellable
                """)
                .param(UUID.randomUUID()).param(orgId).param(productId).param(unitId)
                .param(factor).param(purchasable).param(sellable)
                .update();
    }

    Optional<BigDecimal> findConversionFactor(UUID productId, UUID unitId) {
        return jdbc.sql("""
                SELECT factor_to_stocking FROM app.unit_conversion
                WHERE product_id = ? AND unit_id = ?
                """)
                .param(productId).param(unitId)
                .query(BigDecimal.class)
                .optional();
    }

    // ----------------------------------------------------------------- mapping

    record ProductRow(UUID id, String sku, String name, UUID categoryId, UUID stockingUnitId,
                      UUID taxClassId, boolean stocked, boolean hasVariants, String status) {
    }

    record VariantRow(UUID id, String sku, String name, String axisValuesJson,
                      BigDecimal averageCost, BigDecimal basePrice, boolean isDefault,
                      boolean active) {
    }

    private ProductRow mapProduct(ResultSet rs, int rowNum) throws SQLException {
        return new ProductRow(
                rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("name"),
                rs.getObject("category_id", UUID.class),
                rs.getObject("stocking_unit_id", UUID.class),
                rs.getObject("tax_class_id", UUID.class),
                rs.getBoolean("stocked"), rs.getBoolean("has_variants"), rs.getString("status"));
    }

    private VariantRow mapVariant(ResultSet rs, int rowNum) throws SQLException {
        return new VariantRow(
                rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("name"),
                rs.getString("axis_values"), rs.getBigDecimal("average_cost"),
                rs.getBigDecimal("base_price"), rs.getBoolean("is_default"),
                rs.getBoolean("active"));
    }

    private UnitView mapUnit(ResultSet rs, int rowNum) throws SQLException {
        return new UnitView(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("integral"));
    }

    VariantView toView(VariantRow row, List<String> barcodes) {
        return new VariantView(row.id(), row.sku(), row.name(), fromJson(row.axisValuesJson()),
                Money.ofNullable(row.basePrice()), Money.ofNullable(row.averageCost()),
                row.isDefault(), row.active(), barcodes);
    }

    private String toJson(Map<String, String> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise axis values", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read axis values", e);
        }
    }
}
