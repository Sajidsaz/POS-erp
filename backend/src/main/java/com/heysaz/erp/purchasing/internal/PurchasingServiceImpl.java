package com.heysaz.erp.purchasing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.inventory.api.InventoryService;
import com.heysaz.erp.inventory.api.MovementType;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.sequence.DocumentSequenceService;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.purchasing.api.PurchaseOrderStatus;
import com.heysaz.erp.purchasing.api.PurchasingService;

@Service
class PurchasingServiceImpl implements PurchasingService {

    private final PurchasingRepository repository;
    private final InventoryService inventory;
    private final DocumentSequenceService sequences;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    PurchasingServiceImpl(PurchasingRepository repository, InventoryService inventory,
                          DocumentSequenceService sequences, IdempotencyService idempotency,
                          AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.inventory = inventory;
        this.sequences = sequences;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------- Suppliers

    @Override
    @Transactional
    public SupplierView createSupplier(CreateSupplierCommand command) {
        UUID id = UUID.randomUUID();
        repository.insertSupplier(id, TenantContext.requireOrgId(), command.code(), command.name(),
                command.contactName(), command.phone(), command.email(), command.taxRegistrationNo());
        audit.record("purchasing.supplier_created", "Supplier", id.toString(), null, command,
                AuditService.Outcome.SUCCESS);
        return new SupplierView(id, command.code(), command.name(), command.contactName(),
                command.phone(), command.email(), command.taxRegistrationNo(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierView> listSuppliers() {
        return repository.listSuppliers();
    }

    // -------------------------------------------------------- Purchase orders

    @Override
    @Transactional
    public PurchaseOrderView createPurchaseOrder(CreatePurchaseOrderCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreatePurchaseOrder(command);
        }
        return idempotency.execute("POST /api/v1/purchasing/orders", idempotencyKey, command,
                PurchaseOrderView.class, () -> doCreatePurchaseOrder(command)).value();
    }

    private PurchaseOrderView doCreatePurchaseOrder(CreatePurchaseOrderCommand command) {
        checkShopAccess(command.shopId());
        if (!repository.supplierExists(command.supplierId())) {
            throw ApiException.notFound("Supplier " + command.supplierId());
        }
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        Money orderedTotal = Money.ZERO;
        for (PurchaseOrderLineInput line : command.lines()) {
            orderedTotal = orderedTotal.plus(line.unitCost().times(line.quantity()));
        }

        UUID poId = UUID.randomUUID();
        String poNumber = sequences.nextDocumentNumber(command.shopId(), "PURCHASE_ORDER");
        repository.insertPurchaseOrder(poId, orgId, poNumber, command.supplierId(), command.shopId(),
                PurchaseOrderStatus.DRAFT, orderedTotal.amount(), command.notes(), principal.userId());

        for (PurchaseOrderLineInput line : command.lines()) {
            repository.insertPurchaseOrderLine(UUID.randomUUID(), orgId, poId, line.variantId(),
                    line.quantity().setScale(Money.SCALE, RoundingMode.HALF_UP), line.unitCost().amount());
        }

        PurchaseOrderView view = repository.findPurchaseOrder(poId).orElseThrow();
        audit.record("purchasing.po_created", "PurchaseOrder", poId.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("purchasing.po_created", "PurchaseOrder", poId.toString(), view);
        return view;
    }

    @Override
    @Transactional
    public PurchaseOrderView approvePurchaseOrder(UUID purchaseOrderId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doApprovePurchaseOrder(purchaseOrderId);
        }
        return idempotency.execute("POST /api/v1/purchasing/orders/" + purchaseOrderId + "/approve",
                idempotencyKey, Map.of("purchaseOrderId", purchaseOrderId),
                PurchaseOrderView.class, () -> doApprovePurchaseOrder(purchaseOrderId)).value();
    }

    private PurchaseOrderView doApprovePurchaseOrder(UUID purchaseOrderId) {
        PurchaseOrderView po = repository.findPurchaseOrder(purchaseOrderId)
                .orElseThrow(() -> ApiException.notFound("Purchase order"));
        checkShopAccess(po.shopId());
        if (po.status() != PurchaseOrderStatus.DRAFT) {
            throw ApiException.conflict("Purchase order is " + po.status() + "; only DRAFT can be approved");
        }
        repository.markPurchaseOrderApproved(purchaseOrderId, TenantContext.requirePrincipal().userId());
        PurchaseOrderView updated = repository.findPurchaseOrder(purchaseOrderId).orElseThrow();
        audit.record("purchasing.po_approved", "PurchaseOrder", purchaseOrderId.toString(),
                po, updated, AuditService.Outcome.SUCCESS);
        outbox.publish("purchasing.po_approved", "PurchaseOrder", purchaseOrderId.toString(), updated);
        return updated;
    }

    @Override
    @Transactional
    public void cancelPurchaseOrder(UUID purchaseOrderId, String reason) {
        PurchaseOrderView po = repository.findPurchaseOrder(purchaseOrderId)
                .orElseThrow(() -> ApiException.notFound("Purchase order"));
        checkShopAccess(po.shopId());
        if (po.status() != PurchaseOrderStatus.DRAFT && po.status() != PurchaseOrderStatus.APPROVED) {
            throw ApiException.conflict("Cannot cancel a purchase order in " + po.status() + " status");
        }
        repository.updatePurchaseOrderStatus(purchaseOrderId, PurchaseOrderStatus.CANCELLED);
        audit.record("purchasing.po_cancelled", "PurchaseOrder", purchaseOrderId.toString(),
                po, Map.of("reason", reason == null ? "" : reason), AuditService.Outcome.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrderView getPurchaseOrder(UUID purchaseOrderId) {
        PurchaseOrderView po = repository.findPurchaseOrder(purchaseOrderId)
                .orElseThrow(() -> ApiException.notFound("Purchase order"));
        checkShopAccess(po.shopId());
        return po;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseOrderView> listPurchaseOrders(UUID shopId, PurchaseOrderStatus status) {
        if (shopId != null) {
            checkShopAccess(shopId);
        }
        return repository.listPurchaseOrders(shopId, status);
    }

    // --------------------------------------------------------- Goods receipts

    @Override
    @Transactional
    public GoodsReceiptView receiveGoods(ReceiveGoodsCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doReceiveGoods(command);
        }
        return idempotency.execute("POST /api/v1/purchasing/goods-receipts", idempotencyKey, command,
                GoodsReceiptView.class, () -> doReceiveGoods(command)).value();
    }

    private GoodsReceiptView doReceiveGoods(ReceiveGoodsCommand command) {
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        UUID poId = command.purchaseOrderId();
        UUID supplierId = command.supplierId();
        UUID shopId = command.shopId();

        if (poId != null) {
            PurchaseOrderView po = repository.findPurchaseOrder(poId)
                    .orElseThrow(() -> ApiException.notFound("Purchase order " + poId));
            if (po.status() != PurchaseOrderStatus.APPROVED
                    && po.status() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
                throw ApiException.conflict("Purchase order is " + po.status()
                        + "; only APPROVED or PARTIALLY_RECEIVED orders can be received");
            }
            supplierId = po.supplierId();
            shopId = po.shopId();
        } else if (supplierId == null) {
            throw ApiException.conflict("A direct goods receipt must name a supplier");
        }
        checkShopAccess(shopId);

        // Resolve and validate every line before anything is written.
        record Resolved(ReceiveLineInput input, BigDecimal quantity, BigDecimal unitCost) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (ReceiveLineInput line : command.lines()) {
            BigDecimal qty = line.receivedQuantity().setScale(Money.SCALE, RoundingMode.HALF_UP);
            BigDecimal unitCost;
            if (line.poLineId() != null) {
                PurchasingRepository.PoLineRow poLine = repository.findPoLine(line.poLineId())
                        .orElseThrow(() -> ApiException.notFound("Purchase order line " + line.poLineId()));
                if (poId == null || !poLine.poId().equals(poId)) {
                    throw ApiException.conflict("Purchase order line " + line.poLineId()
                            + " does not belong to the named purchase order");
                }
                if (!poLine.variantId().equals(line.variantId())) {
                    throw ApiException.conflict("Receipt line variant does not match the purchase order line");
                }
                BigDecimal remaining = poLine.orderedQuantity().subtract(poLine.receivedQuantity());
                if (qty.compareTo(remaining) > 0) {
                    throw ApiException.conflict("Cannot receive " + qty
                            + "; only " + remaining + " remain outstanding on the order line");
                }
                unitCost = line.unitCost() != null ? line.unitCost().amount() : poLine.unitCost();
            } else {
                if (line.unitCost() == null) {
                    throw ApiException.conflict("A receipt line without a purchase order line must carry a unit cost");
                }
                unitCost = line.unitCost().amount();
            }
            resolved.add(new Resolved(line, qty, unitCost.setScale(Money.SCALE, RoundingMode.HALF_UP)));
        }

        String grnNumber = sequences.nextDocumentNumber(shopId, "GOODS_RECEIPT");
        UUID grnId = UUID.randomUUID();
        repository.insertGoodsReceipt(grnId, orgId, grnNumber, poId, supplierId, shopId,
                command.notes(), principal.userId());

        for (Resolved r : resolved) {
            ReceiveLineInput line = r.input();
            repository.insertGoodsReceiptLine(UUID.randomUUID(), orgId, grnId, line.poLineId(),
                    line.variantId(), r.quantity(), r.unitCost());

            // Decision D2: this is where new stock enters and the moving average is updated.
            inventory.recordMovement(shopId, line.variantId(), MovementType.RECEIPT,
                    r.quantity(), Money.of(r.unitCost()), "GOODS_RECEIPT", grnNumber,
                    "Goods receipt " + grnNumber);

            if (line.poLineId() != null) {
                repository.incrementPoLineReceived(line.poLineId(), r.quantity());
            }
        }

        if (poId != null) {
            updatePurchaseOrderReceiptStatus(poId);
        }

        GoodsReceiptView view = repository.findGoodsReceipt(grnId).orElseThrow();
        audit.record("purchasing.goods_received", "GoodsReceipt", grnId.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("purchasing.goods_received", "GoodsReceipt", grnId.toString(), view);
        return view;
    }

    private void updatePurchaseOrderReceiptStatus(UUID poId) {
        PurchaseOrderView po = repository.findPurchaseOrder(poId).orElseThrow();
        boolean allReceived = po.lines().stream()
                .allMatch(l -> l.receivedQuantity().compareTo(l.orderedQuantity()) >= 0);
        repository.updatePurchaseOrderStatus(poId,
                allReceived ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Override
    @Transactional(readOnly = true)
    public GoodsReceiptView getGoodsReceipt(UUID goodsReceiptId) {
        GoodsReceiptView view = repository.findGoodsReceipt(goodsReceiptId)
                .orElseThrow(() -> ApiException.notFound("Goods receipt"));
        checkShopAccess(view.shopId());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoodsReceiptView> listGoodsReceipts(UUID shopId) {
        checkShopAccess(shopId);
        return repository.listGoodsReceipts(shopId);
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}
