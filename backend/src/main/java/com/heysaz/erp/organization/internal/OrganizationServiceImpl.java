package com.heysaz.erp.organization.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.organization.api.OrganizationService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository repository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    OrganizationServiceImpl(OrganizationRepository repository, IdempotencyService idempotency,
                            AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public ShopView createShop(CreateShopCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateShop(command);
        }
        return idempotency.execute("POST /api/v1/shops", idempotencyKey, command,
                ShopView.class, () -> doCreateShop(command)).value();
    }

    private ShopView doCreateShop(CreateShopCommand command) {
        repository.findShopByCode(command.code()).ifPresent(existing -> {
            throw ApiException.conflict("Shop code already exists: " + command.code());
        });
        // The prefix is part of every invoice number this shop ever issues, so a collision
        // would make two shops' documents indistinguishable after the fact.
        repository.findShopByPrefix(command.documentPrefix()).ifPresent(existing -> {
            throw ApiException.conflict(
                    "Document prefix already in use: " + command.documentPrefix());
        });

        UUID id = UUID.randomUUID();
        repository.insertShop(id, TenantContext.requireOrgId(), command.code(), command.name(),
                command.documentPrefix(), command.addressLine1(), command.city(),
                command.phone(), command.timezone(), command.taxRegistrationNo(),
                command.sellingEnabled());

        ShopView view = repository.findShop(id).orElseThrow();
        audit.record("shop.created", "Shop", id.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("shop.created", "Shop", id.toString(), view);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShopView> listShops() {
        return repository.findShops();
    }

    @Override
    @Transactional(readOnly = true)
    public ShopView getShop(UUID id) {
        return repository.findShop(id).orElseThrow(() -> ApiException.notFound("Shop"));
    }

    @Override
    @Transactional
    public TerminalView createTerminal(CreateTerminalCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateTerminal(command);
        }
        return idempotency.execute("POST /api/v1/terminals", idempotencyKey, command,
                TerminalView.class, () -> doCreateTerminal(command)).value();
    }

    private TerminalView doCreateTerminal(CreateTerminalCommand command) {
        // Resolving the shop through the repository means RLS has already vetted it: a
        // shop id belonging to another tenant simply is not found.
        ShopView shop = repository.findShop(command.shopId())
                .orElseThrow(() -> ApiException.notFound("Shop"));
        if (!shop.sellingEnabled()) {
            // FR-ORG-008: a warehouse is a shop, but a till in a warehouse is a mistake.
            throw ApiException.conflict("Shop " + shop.code() + " has selling disabled");
        }
        repository.findTerminalByCode(command.code()).ifPresent(existing -> {
            throw ApiException.conflict("Terminal code already exists: " + command.code());
        });

        UUID id = UUID.randomUUID();
        repository.insertTerminal(id, TenantContext.requireOrgId(), command.shopId(),
                command.code(), command.name(),
                command.printerType() == null ? "ESCPOS_USB" : command.printerType(),
                command.printerAddress(),
                command.paperWidthMm() == null ? 80 : command.paperWidthMm(),
                command.cashDrawerEnabled() == null || command.cashDrawerEnabled(),
                command.customerDisplayEnabled() != null && command.customerDisplayEnabled());

        TerminalView view = repository.findTerminal(id).orElseThrow();
        audit.record("terminal.created", "Terminal", id.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TerminalView> listTerminals(UUID shopId) {
        return repository.findTerminals(shopId);
    }

    @Override
    @Transactional
    public void disableTerminal(UUID id, String reason) {
        TerminalView before = repository.findTerminal(id)
                .orElseThrow(() -> ApiException.notFound("Terminal"));
        if (repository.disableTerminal(id) == 0) {
            throw ApiException.conflict("Terminal is already disabled");
        }
        audit.record("terminal.disabled", "Terminal", id.toString(), before,
                java.util.Map.of("reason", reason == null ? "" : reason),
                AuditService.Outcome.SUCCESS);
        // FR-TERM-004 in full: the row is marked and the credential stops working. M3
        // issues terminal credentials; until then there is nothing yet to revoke, and the
        // disabled_at check on login is what enforces it.
        outbox.publish("terminal.disabled", "Terminal", id.toString(),
                java.util.Map.of("terminalId", id.toString(), "reason", reason == null ? "" : reason));
    }
}
