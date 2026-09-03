package com.heysaz.erp.integration.internal;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.integration.api.IntegrationService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class IntegrationServiceImpl implements IntegrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IntegrationRepository repository;
    private final AuditService audit;

    IntegrationServiceImpl(IntegrationRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Override
    @Transactional
    public CreatedSubscriptionView createSubscription(CreateSubscriptionCommand command) {
        UUID id = UUID.randomUUID();
        String secret = generateSecret();
        UUID createdBy = TenantContext.principal().map(p -> p.userId()).orElse(null);
        repository.insertSubscription(id, TenantContext.requireOrgId(), command.url(), secret,
                command.eventTypes(), command.description(), createdBy);
        // The secret is deliberately absent from the audit record.
        audit.record("integration.webhook_subscribed", "WebhookSubscription", id.toString(), null,
                command, AuditService.Outcome.SUCCESS);
        SubscriptionView view = repository.findSubscription(id).orElseThrow();
        return new CreatedSubscriptionView(view, secret);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionView> listSubscriptions() {
        return repository.listSubscriptions();
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionView getSubscription(UUID subscriptionId) {
        return repository.findSubscription(subscriptionId)
                .orElseThrow(() -> ApiException.notFound("Webhook subscription"));
    }

    @Override
    @Transactional
    public SubscriptionView setSubscriptionActive(UUID subscriptionId, boolean active) {
        getSubscription(subscriptionId); // 404 if unknown / not this tenant's
        repository.setActive(subscriptionId, active);
        return repository.findSubscription(subscriptionId).orElseThrow();
    }

    @Override
    @Transactional
    public void deleteSubscription(UUID subscriptionId) {
        if (repository.deleteSubscription(subscriptionId) == 0) {
            throw ApiException.notFound("Webhook subscription");
        }
        audit.record("integration.webhook_deleted", "WebhookSubscription", subscriptionId.toString(),
                null, null, AuditService.Outcome.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryView> listDeliveries(UUID subscriptionId, int limit) {
        getSubscription(subscriptionId);
        return repository.listDeliveries(subscriptionId, Math.clamp(limit, 1, 500));
    }

    /** A URL-safe shared secret shown once. The {@code whsec_} prefix aids recognition. */
    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }
}
