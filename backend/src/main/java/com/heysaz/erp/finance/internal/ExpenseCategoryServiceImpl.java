package com.heysaz.erp.finance.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.finance.api.ExpenseCategoryService;
import com.heysaz.erp.finance.domain.ExpenseCategory;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.TenantContext;

/**
 * The M0 vertical slice. Small on purpose, but it exercises every kernel mechanic in one
 * transaction — tenant binding, idempotency, audit and outbox — which is what makes it a
 * usable template for the modules that follow rather than a demo.
 */
@Service
class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private static final String ENDPOINT = "POST /api/v1/expense-categories";

    private final ExpenseCategoryRepository repository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    ExpenseCategoryServiceImpl(ExpenseCategoryRepository repository,
                               IdempotencyService idempotency,
                               AuditService audit,
                               OutboxPublisher outbox) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public View create(CreateCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreate(command);
        }
        // The idempotency record and the row it protects commit together (FR-API-012).
        return idempotency
                .execute(ENDPOINT, idempotencyKey, command, View.class, () -> doCreate(command))
                .value();
    }

    private View doCreate(CreateCommand command) {
        // FR-CAT-006 in spirit, DB-003 in mechanism: the unique index is scoped to the
        // organization, so this check races only against the same tenant, and the
        // constraint is still the final arbiter.
        repository.findByCode(command.code()).ifPresent(existing -> {
            throw ApiException.conflict("Expense category code already exists: " + command.code());
        });

        ExpenseCategory category = new ExpenseCategory(
                UUID.randomUUID(),
                TenantContext.requireOrgId(),
                command.code(),
                command.name(),
                command.monthlyBudget(),
                true,
                Instant.now(),
                Instant.now());
        repository.insert(category);

        View view = toView(category);
        audit.record("expense_category.created", "ExpenseCategory", category.id().toString(),
                null, view, AuditService.Outcome.SUCCESS);
        outbox.publish("expense_category.created", "ExpenseCategory",
                category.id().toString(), view);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<View> listActive() {
        return repository.findActive().stream().map(ExpenseCategoryServiceImpl::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public View get(UUID id) {
        // A row belonging to another tenant is invisible to the policy, so this is a 404
        // rather than a 403 — which is the non-disclosure behaviour FR-PERM-001 wants.
        return repository.findById(id)
                .map(ExpenseCategoryServiceImpl::toView)
                .orElseThrow(() -> ApiException.notFound("Expense category"));
    }

    private static View toView(ExpenseCategory category) {
        return new View(category.id(), category.code(), category.name(),
                category.monthlyBudget(), category.active());
    }
}
