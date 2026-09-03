package com.heysaz.erp.integration.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

/**
 * Webhook subscription management and delivery visibility (Section 14.3).
 *
 * <p>A subscription's signing secret is generated server-side and returned exactly once, at
 * creation — it is never read back, matching how integration token secrets are handled
 * (SEC-010). Delivery records are read-only history written by the outbox worker.
 */
public interface IntegrationService {

    record CreateSubscriptionCommand(
            @NotBlank @Pattern(regexp = "https?://.+", message = "must be an http(s) URL") String url,
            /** Event types to receive, e.g. ["pos.sale_completed"]; ["*"] for every event. */
            @NotEmpty List<String> eventTypes,
            String description) {
    }

    record SubscriptionView(
            UUID id,
            String url,
            List<String> eventTypes,
            String description,
            boolean active,
            Instant createdAt) {
    }

    /** Returned only from {@link #createSubscription}: the secret is shown once and never again. */
    record CreatedSubscriptionView(SubscriptionView subscription, String secret) {
    }

    record DeliveryView(
            UUID id,
            UUID subscriptionId,
            String eventType,
            String url,
            String status,
            int attempts,
            Integer responseStatus,
            String lastError,
            Instant createdAt,
            Instant deliveredAt) {
    }

    CreatedSubscriptionView createSubscription(CreateSubscriptionCommand command);

    List<SubscriptionView> listSubscriptions();

    SubscriptionView getSubscription(UUID subscriptionId);

    SubscriptionView setSubscriptionActive(UUID subscriptionId, boolean active);

    void deleteSubscription(UUID subscriptionId);

    List<DeliveryView> listDeliveries(UUID subscriptionId, int limit);
}
