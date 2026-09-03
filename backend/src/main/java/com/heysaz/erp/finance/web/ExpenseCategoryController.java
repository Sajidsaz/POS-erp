package com.heysaz.erp.finance.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.finance.api.ExpenseCategoryService;

import jakarta.validation.Valid;

/** API-STD-001: versioned path. Authorization is server-side on every method (SEC-003). */
@RestController
@RequestMapping("/api/v1/expense-categories")
class ExpenseCategoryController {

    private final ExpenseCategoryService service;

    ExpenseCategoryController(ExpenseCategoryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('expenses.write')")
    ExpenseCategoryService.View create(
            @Valid @RequestBody ExpenseCategoryService.CreateCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.create(command, idempotencyKey);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('expenses.read')")
    List<ExpenseCategoryService.View> list() {
        return service.listActive();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('expenses.read')")
    ExpenseCategoryService.View get(@PathVariable UUID id) {
        return service.get(id);
    }
}
