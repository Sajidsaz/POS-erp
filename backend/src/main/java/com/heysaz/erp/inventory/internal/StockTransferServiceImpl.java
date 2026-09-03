package com.heysaz.erp.inventory.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.inventory.api.MovementType;
import com.heysaz.erp.inventory.api.StockTransferService;
import com.heysaz.erp.inventory.api.TransferStatus;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.sequence.DocumentSequenceService;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class StockTransferServiceImpl implements StockTransferService {

    private final StockTransferRepository repository;
    private final InventoryRepository inventoryRepository;
    private final DocumentSequenceService sequenceService;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    StockTransferServiceImpl(StockTransferRepository repository,
                             InventoryRepository inventoryRepository,
                             DocumentSequenceService sequenceService,
                             IdempotencyService idempotency,
                             AuditService audit,
                             OutboxPublisher outbox) {
        this.repository = repository;
        this.inventoryRepository = inventoryRepository;
        this.sequenceService = sequenceService;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public StockTransferView createTransfer(CreateTransferCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateTransfer(command);
        }
        return idempotency.execute("POST /api/v1/stock-transfers", idempotencyKey, command,
                StockTransferView.class, () -> doCreateTransfer(command)).value();
    }

    private StockTransferView doCreateTransfer(CreateTransferCommand command) {
        if (command.sourceShopId().equals(command.destinationShopId())) {
            throw ApiException.conflict("Source and destination shop must be different");
        }
        checkShopAccess(command.sourceShopId());

        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();
        UUID transferId = UUID.randomUUID();

        // Gapless document sequence (Decision D5)
        String transferNumber = sequenceService.nextDocumentNumber(command.sourceShopId(), "TRANSFER");

        repository.insertTransfer(transferId, orgId, transferNumber, command.sourceShopId(),
                command.destinationShopId(), TransferStatus.DRAFT, command.notes(), principal.userId());

        // Lock source balances to capture current moving average cost
        List<UUID> variantIds = command.lines().stream().map(TransferLineInput::variantId).distinct().toList();
        Map<UUID, InventoryRepository.BalanceRow> balanceMap = inventoryRepository
                .lockBalances(command.sourceShopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        for (TransferLineInput line : command.lines()) {
            InventoryRepository.BalanceRow balance = balanceMap.get(line.variantId());
            BigDecimal unitCost = (balance != null && balance.averageCost() != null)
                    ? balance.averageCost()
                    : BigDecimal.ZERO;

            repository.insertTransferLine(UUID.randomUUID(), orgId, transferId, line.variantId(),
                    line.dispatchedQuantity().setScale(4, RoundingMode.HALF_UP), unitCost);
        }

        StockTransferView view = repository.findTransfer(transferId).orElseThrow();
        audit.record("stock_transfer.created", "StockTransfer", transferId.toString(),
                null, view, AuditService.Outcome.SUCCESS);
        outbox.publish("stock_transfer.created", "StockTransfer", transferId.toString(), view);
        return view;
    }

    @Override
    @Transactional
    public StockTransferView dispatchTransfer(UUID transferId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doDispatchTransfer(transferId);
        }
        return idempotency.execute("POST /api/v1/stock-transfers/" + transferId + "/dispatch",
                idempotencyKey, Map.of("transferId", transferId),
                StockTransferView.class, () -> doDispatchTransfer(transferId)).value();
    }

    private StockTransferView doDispatchTransfer(UUID transferId) {
        StockTransferView transfer = repository.findTransfer(transferId)
                .orElseThrow(() -> ApiException.notFound("Stock transfer"));
        if (transfer.status() != TransferStatus.DRAFT) {
            throw ApiException.conflict("Transfer is in " + transfer.status() + " state; only DRAFT can be dispatched");
        }
        checkShopAccess(transfer.sourceShopId());

        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        List<UUID> variantIds = transfer.lines().stream().map(TransferLineView::variantId).distinct().toList();
        Map<UUID, InventoryRepository.BalanceRow> sourceBalances = inventoryRepository
                .lockBalances(transfer.sourceShopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        boolean allowNegative = inventoryRepository.isNegativeStockAllowed(transfer.sourceShopId())
                || principal.hasAuthority("negative_stock.override");

        for (TransferLineView line : transfer.lines()) {
            InventoryRepository.BalanceRow balance = sourceBalances.get(line.variantId());
            BigDecimal qty = line.dispatchedQuantity();
            BigDecimal currentOnHand = balance.quantityOnHand();
            BigDecimal newOnHand = currentOnHand.subtract(qty).setScale(4, RoundingMode.HALF_UP);

            if (newOnHand.signum() < 0 && !allowNegative) {
                throw ApiException.conflict("Insufficient stock for variant " + line.variantSku() + " at source shop");
            }

            BigDecimal currentInTransit = balance.quantityInTransit();
            BigDecimal newInTransit = currentInTransit.add(qty).setScale(4, RoundingMode.HALF_UP);
            BigDecimal unitCost = line.unitCost().amount();
            BigDecimal totalCost = qty.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);

            // Deduct on_hand, add to in_transit
            inventoryRepository.updateBalance(transfer.sourceShopId(), line.variantId(),
                    newOnHand, balance.quantityReserved(), newInTransit, balance.averageCost());

            // Write immutable TRANSFER_OUT movement
            inventoryRepository.insertMovement(
                    UUID.randomUUID(), orgId, transfer.sourceShopId(), line.variantId(),
                    MovementType.TRANSFER_OUT, qty.negate(), unitCost, totalCost,
                    newOnHand, balance.averageCost(),
                    "STOCK_TRANSFER", transfer.transferNumber(),
                    "Dispatched to destination shop", principal.userId());
        }

        repository.markDispatched(transferId, principal.userId());

        StockTransferView updated = repository.findTransfer(transferId).orElseThrow();
        audit.record("stock_transfer.dispatched", "StockTransfer", transferId.toString(),
                transfer, updated, AuditService.Outcome.SUCCESS);
        outbox.publish("stock_transfer.dispatched", "StockTransfer", transferId.toString(), updated);
        return updated;
    }

    @Override
    @Transactional
    public StockTransferView receiveTransfer(UUID transferId, ReceiveTransferCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doReceiveTransfer(transferId, command);
        }
        return idempotency.execute("POST /api/v1/stock-transfers/" + transferId + "/receive",
                idempotencyKey, command,
                StockTransferView.class, () -> doReceiveTransfer(transferId, command)).value();
    }

    private StockTransferView doReceiveTransfer(UUID transferId, ReceiveTransferCommand command) {
        StockTransferView transfer = repository.findTransfer(transferId)
                .orElseThrow(() -> ApiException.notFound("Stock transfer"));
        if (transfer.status() != TransferStatus.DISPATCHED && transfer.status() != TransferStatus.PARTIALLY_RECEIVED) {
            throw ApiException.conflict("Transfer is in " + transfer.status() + " state and cannot be received");
        }
        checkShopAccess(transfer.destinationShopId());

        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        Map<UUID, TransferLineView> lineMap = transfer.lines().stream()
                .collect(Collectors.toMap(TransferLineView::id, l -> l));

        List<UUID> variantIds = transfer.lines().stream().map(TransferLineView::variantId).distinct().toList();

        // Lock balances at source (to clear in_transit) and destination (to add on_hand)
        Map<UUID, InventoryRepository.BalanceRow> sourceBalances = inventoryRepository
                .lockBalances(transfer.sourceShopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        Map<UUID, InventoryRepository.BalanceRow> destBalances = inventoryRepository
                .lockBalances(transfer.destinationShopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        boolean anyReceived = false;
        boolean allFullyReceived = true;

        for (ReceiveLineInput input : command.lines()) {
            TransferLineView line = lineMap.get(input.lineId());
            if (line == null) {
                throw ApiException.notFound("Transfer line " + input.lineId());
            }

            BigDecimal dispatched = line.dispatchedQuantity();
            BigDecimal received = input.receivedQuantity().setScale(4, RoundingMode.HALF_UP);
            BigDecimal unitCost = line.unitCost().amount();

            if (received.compareTo(dispatched) > 0) {
                throw ApiException.conflict("Received quantity cannot exceed dispatched quantity for variant " + line.variantSku());
            }

            // Deduct in_transit at source
            InventoryRepository.BalanceRow srcBalance = sourceBalances.get(line.variantId());
            BigDecimal newSrcInTransit = srcBalance.quantityInTransit().subtract(dispatched).setScale(4, RoundingMode.HALF_UP);
            if (newSrcInTransit.signum() < 0) {
                newSrcInTransit = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }
            inventoryRepository.updateBalance(transfer.sourceShopId(), line.variantId(),
                    srcBalance.quantityOnHand(), srcBalance.quantityReserved(), newSrcInTransit, srcBalance.averageCost());

            // Handle actual received stock at destination
            if (received.signum() > 0) {
                anyReceived = true;
                InventoryRepository.BalanceRow destBalance = destBalances.get(line.variantId());
                BigDecimal currentDestOnHand = destBalance.quantityOnHand();
                BigDecimal newDestOnHand = currentDestOnHand.add(received).setScale(4, RoundingMode.HALF_UP);

                // Decision D2: Recalculate moving average cost at destination
                BigDecimal currentDestAvgCost = destBalance.averageCost() == null ? BigDecimal.ZERO : destBalance.averageCost();
                BigDecimal newDestAvgCost;
                if (currentDestOnHand.signum() <= 0) {
                    newDestAvgCost = unitCost;
                } else {
                    BigDecimal currentVal = currentDestOnHand.multiply(currentDestAvgCost);
                    BigDecimal incomingVal = received.multiply(unitCost);
                    newDestAvgCost = currentVal.add(incomingVal).divide(newDestOnHand, 4, RoundingMode.HALF_UP);
                }

                BigDecimal totalCost = received.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);

                inventoryRepository.updateBalance(transfer.destinationShopId(), line.variantId(),
                        newDestOnHand, destBalance.quantityReserved(), destBalance.quantityInTransit(), newDestAvgCost);

                // Write immutable TRANSFER_IN movement at destination
                inventoryRepository.insertMovement(
                        UUID.randomUUID(), orgId, transfer.destinationShopId(), line.variantId(),
                        MovementType.TRANSFER_IN, received, unitCost, totalCost,
                        newDestOnHand, newDestAvgCost,
                        "STOCK_TRANSFER", transfer.transferNumber(),
                        "Received from shop " + transfer.sourceShopId(), principal.userId());
            }

            // Discrepancy handling (FR-INV-011): shortage produces recorded write-off
            if (received.compareTo(dispatched) < 0) {
                allFullyReceived = false;
                BigDecimal discrepancy = dispatched.subtract(received).setScale(4, RoundingMode.HALF_UP);
                if (input.discrepancyReason() == null || input.discrepancyReason().isBlank()) {
                    throw ApiException.conflict("Discrepancy reason is mandatory when received quantity differs from dispatched");
                }

                // Write shrinkage movement at source shop for the lost transit goods
                BigDecimal shrinkageTotalCost = discrepancy.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
                inventoryRepository.insertMovement(
                        UUID.randomUUID(), orgId, transfer.sourceShopId(), line.variantId(),
                        MovementType.WRITE_OFF, BigDecimal.ZERO, unitCost, shrinkageTotalCost,
                        srcBalance.quantityOnHand(), srcBalance.averageCost(),
                        "STOCK_TRANSFER_SHRINKAGE", transfer.transferNumber(),
                        "Transfer discrepancy: " + input.discrepancyReason(), principal.userId());
            }

            repository.updateTransferLineReceived(input.lineId(), received, input.discrepancyReason());
        }

        TransferStatus finalStatus = allFullyReceived
                ? TransferStatus.RECEIVED
                : (anyReceived ? TransferStatus.PARTIALLY_RECEIVED : TransferStatus.RECEIVED);

        repository.markReceived(transferId, finalStatus, principal.userId());

        StockTransferView updated = repository.findTransfer(transferId).orElseThrow();
        audit.record("stock_transfer.received", "StockTransfer", transferId.toString(),
                transfer, updated, AuditService.Outcome.SUCCESS);
        outbox.publish("stock_transfer.received", "StockTransfer", transferId.toString(), updated);
        return updated;
    }

    @Override
    @Transactional
    public void cancelTransfer(UUID transferId, String reason) {
        StockTransferView transfer = repository.findTransfer(transferId)
                .orElseThrow(() -> ApiException.notFound("Stock transfer"));
        if (transfer.status() != TransferStatus.DRAFT) {
            throw ApiException.conflict("Cannot cancel transfer in " + transfer.status() + " status");
        }
        checkShopAccess(transfer.sourceShopId());

        repository.cancelTransfer(transferId, reason);
        audit.record("stock_transfer.cancelled", "StockTransfer", transferId.toString(),
                transfer, Map.of("reason", reason == null ? "" : reason), AuditService.Outcome.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public StockTransferView getTransfer(UUID transferId) {
        StockTransferView view = repository.findTransfer(transferId)
                .orElseThrow(() -> ApiException.notFound("Stock transfer"));
        checkShopAccess(view.sourceShopId());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockTransferView> listTransfers(UUID shopId, TransferStatus status) {
        if (shopId != null) {
            checkShopAccess(shopId);
        }
        return repository.listTransfers(shopId, status);
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}
