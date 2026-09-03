package com.heysaz.erp.integration.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.integration.api.IntegrationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/integrations/webhooks")
class IntegrationController {

    private final IntegrationService integrationService;

    IntegrationController(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('api_tokens.write')")
    IntegrationService.CreatedSubscriptionView create(
            @Valid @RequestBody IntegrationService.CreateSubscriptionCommand command) {
        return integrationService.createSubscription(command);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('api_tokens.read')")
    List<IntegrationService.SubscriptionView> list() {
        return integrationService.listSubscriptions();
    }

    @GetMapping("/{subscriptionId}")
    @PreAuthorize("hasAuthority('api_tokens.read')")
    IntegrationService.SubscriptionView get(@PathVariable UUID subscriptionId) {
        return integrationService.getSubscription(subscriptionId);
    }

    @PostMapping("/{subscriptionId}/active")
    @PreAuthorize("hasAuthority('api_tokens.write')")
    IntegrationService.SubscriptionView setActive(
            @PathVariable UUID subscriptionId,
            @RequestParam boolean active) {
        return integrationService.setSubscriptionActive(subscriptionId, active);
    }

    @DeleteMapping("/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('api_tokens.write')")
    void delete(@PathVariable UUID subscriptionId) {
        integrationService.deleteSubscription(subscriptionId);
    }

    @GetMapping("/{subscriptionId}/deliveries")
    @PreAuthorize("hasAuthority('api_tokens.read')")
    List<IntegrationService.DeliveryView> deliveries(
            @PathVariable UUID subscriptionId,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return integrationService.listDeliveries(subscriptionId, limit);
    }
}
