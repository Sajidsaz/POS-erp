package com.heysaz.erp.customer.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.customer.api.CustomerService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository repository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    CustomerServiceImpl(CustomerRepository repository, IdempotencyService idempotency,
                        AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public CustomerView createCustomer(CreateCustomerCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateCustomer(command);
        }
        return idempotency.execute("POST /api/v1/customers", idempotencyKey, command,
                CustomerView.class, () -> doCreateCustomer(command)).value();
    }

    private CustomerView doCreateCustomer(CreateCustomerCommand command) {
        UUID id = UUID.randomUUID();
        BigDecimal limit = command.creditLimit() == null
                ? BigDecimal.ZERO
                : command.creditLimit().amount();
        repository.insertCustomer(id, TenantContext.requireOrgId(), command.code(), command.name(),
                command.phone(), command.email(), limit);
        CustomerView view = repository.findCustomer(id).orElseThrow();
        audit.record("customer.created", "Customer", id.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerView getCustomer(UUID customerId) {
        return repository.findCustomer(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> searchCustomers(String query, int limit) {
        return repository.search(query, Math.clamp(limit, 1, 200));
    }

    @Override
    @Transactional
    public CustomerView setCreditLimit(UUID customerId, Money creditLimit) {
        if (creditLimit == null || creditLimit.isNegative()) {
            throw ApiException.conflict("Credit limit must not be negative");
        }
        CustomerView before = getCustomer(customerId);
        repository.updateCreditLimit(customerId, creditLimit.amount());
        CustomerView after = repository.findCustomer(customerId).orElseThrow();
        // SEC-011: a credit limit change is money-sensitive, so it is audited.
        audit.record("customer.credit_limit_set", "Customer", customerId.toString(), before, after,
                AuditService.Outcome.SUCCESS);
        return after;
    }

    @Override
    @Transactional
    public void chargeCredit(UUID customerId, Money amount, String referenceType, String referenceId) {
        if (amount == null || amount.isNegative() || amount.isZero()) {
            throw ApiException.conflict("A credit charge must be a positive amount");
        }
        CustomerRepository.LockedAccount account = repository.lockCustomer(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer " + customerId));

        BigDecimal newBalance = account.creditBalance().add(amount.amount());
        if (newBalance.compareTo(account.creditLimit()) > 0) {
            BigDecimal available = account.creditLimit().subtract(account.creditBalance()).max(BigDecimal.ZERO);
            throw ApiException.conflict("Charge of " + amount + " exceeds available credit ("
                    + Money.of(available) + ")");
        }

        repository.updateBalance(customerId, newBalance);
        repository.insertLedger(UUID.randomUUID(), TenantContext.requireOrgId(), customerId,
                "CHARGE", amount.amount(), referenceType, referenceId, newBalance, actor());
    }

    @Override
    @Transactional
    public CustomerView recordPayment(RecordPaymentCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doRecordPayment(command);
        }
        return idempotency.execute("POST /api/v1/customers/payments", idempotencyKey, command,
                CustomerView.class, () -> doRecordPayment(command)).value();
    }

    private CustomerView doRecordPayment(RecordPaymentCommand command) {
        Money amount = command.amount();
        if (amount == null || amount.isNegative() || amount.isZero()) {
            throw ApiException.conflict("A payment must be a positive amount");
        }
        CustomerRepository.LockedAccount account = repository.lockCustomer(command.customerId())
                .orElseThrow(() -> ApiException.notFound("Customer " + command.customerId()));

        BigDecimal newBalance = account.creditBalance().subtract(amount.amount());
        repository.updateBalance(command.customerId(), newBalance);
        repository.insertLedger(UUID.randomUUID(), TenantContext.requireOrgId(), command.customerId(),
                "PAYMENT", amount.amount().negate(),
                command.method() == null ? "PAYMENT" : command.method(),
                command.reference(), newBalance, actor());

        CustomerView view = repository.findCustomer(command.customerId()).orElseThrow();
        audit.record("customer.payment_recorded", "Customer", command.customerId().toString(),
                null, view, AuditService.Outcome.SUCCESS);
        outbox.publish("customer.payment_recorded", "Customer", command.customerId().toString(), view);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryView> listLedger(UUID customerId, int limit) {
        getCustomer(customerId); // 404 if unknown
        return repository.listLedger(customerId, Math.clamp(limit, 1, 500));
    }

    private UUID actor() {
        return TenantContext.principal().map(p -> p.userId()).orElse(null);
    }
}
