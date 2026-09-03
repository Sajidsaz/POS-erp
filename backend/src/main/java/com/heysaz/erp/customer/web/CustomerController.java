package com.heysaz.erp.customer.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.customer.api.CustomerService;
import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerService customerService;

    CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    record SetCreditLimitRequest(@NotNull Money creditLimit) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('customers.write')")
    CustomerService.CustomerView create(
            @Valid @RequestBody CustomerService.CreateCustomerCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return customerService.createCustomer(command, idempotencyKey);
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAuthority('customers.read')")
    CustomerService.CustomerView get(@PathVariable UUID customerId) {
        return customerService.getCustomer(customerId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customers.read')")
    List<CustomerService.CustomerView> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return customerService.searchCustomers(q, limit);
    }

    @PutMapping("/{customerId}/credit-limit")
    @PreAuthorize("hasAuthority('customer.credit_limits.write')")
    CustomerService.CustomerView setCreditLimit(
            @PathVariable UUID customerId,
            @Valid @RequestBody SetCreditLimitRequest request) {
        return customerService.setCreditLimit(customerId, request.creditLimit());
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('customers.write')")
    CustomerService.CustomerView recordPayment(
            @Valid @RequestBody CustomerService.RecordPaymentCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return customerService.recordPayment(command, idempotencyKey);
    }

    @GetMapping("/{customerId}/ledger")
    @PreAuthorize("hasAuthority('customers.read')")
    List<CustomerService.LedgerEntryView> ledger(
            @PathVariable UUID customerId,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return customerService.listLedger(customerId, limit);
    }
}
