package com.heysaz.erp.organization.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.organization.api.OrganizationService;

import jakarta.validation.Valid;

/** Shops and terminals both live under settings in the Section 5.2 matrix. */
@RestController
@RequestMapping("/api/v1")
class OrganizationController {

    private final OrganizationService service;

    OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @PostMapping("/shops")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('settings.write')")
    OrganizationService.ShopView createShop(
            @Valid @RequestBody OrganizationService.CreateShopCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.createShop(command, idempotencyKey);
    }

    @GetMapping("/shops")
    @PreAuthorize("hasAuthority('settings.read')")
    List<OrganizationService.ShopView> listShops() {
        return service.listShops();
    }

    @GetMapping("/shops/{id}")
    @PreAuthorize("hasAuthority('settings.read')")
    OrganizationService.ShopView getShop(@PathVariable UUID id) {
        return service.getShop(id);
    }

    @PostMapping("/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('settings.write')")
    OrganizationService.TerminalView createTerminal(
            @Valid @RequestBody OrganizationService.CreateTerminalCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.createTerminal(command, idempotencyKey);
    }

    @GetMapping("/terminals")
    @PreAuthorize("hasAuthority('settings.read')")
    List<OrganizationService.TerminalView> listTerminals(
            @RequestParam(required = false) UUID shopId) {
        return service.listTerminals(shopId);
    }

    @PostMapping("/terminals/{id}/disable")
    @PreAuthorize("hasAuthority('settings.write')")
    void disableTerminal(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        service.disableTerminal(id, body == null ? null : body.get("reason"));
    }
}
