package com.heysaz.erp.customer.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Customers and their store-credit accounts (Section 9).
 *
 * <p>Both {@link #chargeCredit} and {@link #recordPayment} take the customer row under a
 * pessimistic lock and write an append-only ledger entry, so the on-row balance stays
 * consistent under concurrent checkouts and can always be reconstructed from the ledger.
 * {@link #chargeCredit} is the on-account tender path the POS calls inside the checkout
 * transaction; it refuses a charge that would breach the credit limit.
 */
public interface CustomerService {

    record CreateCustomerCommand(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 200) String name,
            String phone,
            String email,
            /** Optional opening credit limit; absent means cash-only. */
            Money creditLimit) {
    }

    record CustomerView(
            UUID id,
            String code,
            String name,
            String phone,
            String email,
            Money creditLimit,
            Money creditBalance,
            /** creditLimit − creditBalance, floored at zero. */
            Money availableCredit,
            boolean active) {
    }

    record LedgerEntryView(
            UUID id,
            String entryType,
            /** Signed: positive raised what is owed, negative settled it. */
            Money amount,
            String referenceType,
            String referenceId,
            Money balanceAfter,
            UUID actorUserId,
            Instant createdAt) {
    }

    record RecordPaymentCommand(
            @NotNull UUID customerId,
            @NotNull Money amount,
            /** How the payment was tendered, e.g. CASH or CARD. */
            String method,
            String reference) {
    }

    CustomerView createCustomer(CreateCustomerCommand command, String idempotencyKey);

    CustomerView getCustomer(UUID customerId);

    List<CustomerView> searchCustomers(String query, int limit);

    /** FR-CUST / customer.credit_limits: set or change a customer's credit ceiling. */
    CustomerView setCreditLimit(UUID customerId, Money creditLimit);

    /**
     * Charges an amount to a customer's account, refusing to breach the credit limit.
     * Called by the POS for a {@code CREDIT} tender, inside the checkout transaction.
     */
    void chargeCredit(UUID customerId, Money amount, String referenceType, String referenceId);

    /** Records a payment against a customer's balance (settling what they owe). */
    CustomerView recordPayment(RecordPaymentCommand command, String idempotencyKey);

    List<LedgerEntryView> listLedger(UUID customerId, int limit);
}
