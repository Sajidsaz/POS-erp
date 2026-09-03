package com.heysaz.erp.catalog.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.catalog.api.CatalogService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class CatalogServiceImpl implements CatalogService {

    private final CatalogRepository repository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    CatalogServiceImpl(CatalogRepository repository, IdempotencyService idempotency,
                       AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public ProductView createProduct(CreateProductCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateProduct(command);
        }
        return idempotency.execute("POST /api/v1/products", idempotencyKey, command,
                ProductView.class, () -> doCreateProduct(command)).value();
    }

    private ProductView doCreateProduct(CreateProductCommand command) {
        if (repository.skuExists(command.sku())) {
            throw ApiException.conflict("Product SKU already exists: " + command.sku());
        }
        UUID orgId = TenantContext.requireOrgId();
        UUID productId = UUID.randomUUID();

        List<VariantSpec> specs = command.variants() == null ? List.of() : command.variants();
        boolean hasVariants = !specs.isEmpty();

        repository.insertProduct(productId, orgId, command.sku(), command.name(),
                command.description(), command.categoryId(), command.stockingUnitId(),
                command.taxClassId(),
                command.stocked() == null || command.stocked(), hasVariants);

        if (hasVariants) {
            for (VariantSpec spec : specs) {
                createVariant(orgId, productId, spec.sku(), spec.name(), spec.axisValues(),
                        spec.cost(), spec.price(), spec.barcodes(), false);
            }
        } else {
            // FR-CAT-008: the implicit variant. It carries the product's own SKU so a
            // simple product looks unchanged to anyone scanning or searching for it, while
            // stock and sale lines still get a variant to point at.
            createVariant(orgId, productId, command.sku(), null, Map.of(),
                    command.cost(), command.price(), command.barcodes(), true);
        }

        ProductView view = getProduct(productId);
        audit.record("product.created", "Product", productId.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("product.created", "Product", productId.toString(), view);
        return view;
    }

    private void createVariant(UUID orgId, UUID productId, String sku, String name,
                               Map<String, String> axisValues, Money cost, Money price,
                               List<String> barcodes, boolean isDefault) {
        if (repository.variantSkuExists(sku)) {
            throw ApiException.conflict("Variant SKU already exists: " + sku);
        }
        UUID variantId = UUID.randomUUID();
        repository.insertVariant(variantId, orgId, productId, sku, name, axisValues,
                cost == null ? null : cost.amount(),
                price == null ? null : price.amount(),
                isDefault);

        if (barcodes != null) {
            for (String barcode : barcodes) {
                if (barcode == null || barcode.isBlank()) {
                    continue;
                }
                // FR-CAT-006: a barcode must resolve to one variant, or the till cannot
                // tell what was scanned.
                if (repository.barcodeExists(barcode)) {
                    throw ApiException.conflict("Barcode already assigned: " + barcode);
                }
                repository.insertBarcode(orgId, variantId, barcode, barcodes.indexOf(barcode) == 0);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ProductView getProduct(UUID id) {
        CatalogRepository.ProductRow row = repository.findProduct(id)
                .orElseThrow(() -> ApiException.notFound("Product"));
        return new ProductView(row.id(), row.sku(), row.name(), row.categoryId(),
                row.stockingUnitId(), row.taxClassId(), row.stocked(), row.hasVariants(),
                row.status(), repository.findVariants(row.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> searchProducts(String query, int limit) {
        int capped = Math.clamp(limit, 1, 200);
        return repository.searchProducts(query == null ? "" : query.trim(), capped).stream()
                .map(row -> new ProductView(row.id(), row.sku(), row.name(), row.categoryId(),
                        row.stockingUnitId(), row.taxClassId(), row.stocked(),
                        row.hasVariants(), row.status(), repository.findVariants(row.id())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VariantView findByBarcode(String barcode) {
        return repository.findVariantByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("Barcode"));
    }

    @Override
    @Transactional
    public UnitView createUnit(CreateUnitCommand command) {
        if (repository.unitCodeExists(command.code())) {
            throw ApiException.conflict("Unit code already exists: " + command.code());
        }
        UUID id = UUID.randomUUID();
        repository.insertUnit(id, TenantContext.requireOrgId(), command.code(), command.name(),
                command.integral() == null || command.integral());
        return repository.findUnit(id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitView> listUnits() {
        return repository.findUnits();
    }

    @Override
    @Transactional
    public void addUnitConversion(UUID productId, AddUnitConversionCommand command) {
        CatalogRepository.ProductRow product = repository.findProduct(productId)
                .orElseThrow(() -> ApiException.notFound("Product"));
        if (command.factorToStocking().signum() <= 0) {
            throw ApiException.conflict("Conversion factor must be greater than zero");
        }
        if (product.stockingUnitId().equals(command.unitId())) {
            throw ApiException.conflict("The stocking unit converts to itself by definition");
        }
        repository.insertUnitConversion(TenantContext.requireOrgId(), productId, command.unitId(),
                command.factorToStocking(),
                command.purchasable() == null || command.purchasable(),
                command.sellable() != null && command.sellable());

        // FR-CAT-012: changing a factor must not rewrite history, so this is audited and
        // existing movements keep the converted quantity they were written with.
        audit.record("product.unit_conversion_set", "Product", productId.toString(), null,
                command, AuditService.Outcome.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal toStockingUnits(UUID productId, UUID unitId, BigDecimal quantity) {
        CatalogRepository.ProductRow product = repository.findProduct(productId)
                .orElseThrow(() -> ApiException.notFound("Product"));
        if (unitId == null || product.stockingUnitId().equals(unitId)) {
            return quantity;
        }
        BigDecimal factor = repository.findConversionFactor(productId, unitId)
                .orElseThrow(() -> ApiException.conflict(
                        "No conversion defined from that unit to the stocking unit"));
        return quantity.multiply(factor);
    }
}
