package com.heysaz.erp.notifications.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.notifications.api.NotificationService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository repository;
    private final EmailSender emailSender;

    NotificationServiceImpl(NotificationRepository repository, EmailSender emailSender) {
        this.repository = repository;
        this.emailSender = emailSender;
    }

    @Override
    @Transactional
    public NotificationView raise(RaiseCommand command) {
        UUID orgId = TenantContext.requireOrgId();
        UUID id = UUID.randomUUID();
        // In-app is the always-available channel (FR-NOT-002).
        repository.insert(id, orgId, command.userId(), command.type().name(),
                command.severity().name(), command.title(), command.body(),
                command.referenceType(), command.referenceId());

        // Email only for a targeted user who has enabled it, and only if a provider exists.
        // FR-NOT-004: the email body never carries the detail — it points back to the app.
        if (command.userId() != null && emailSender.isConfigured()
                && preferenceEnabled(command.userId(), command.type(), Channel.EMAIL)) {
            emailSender.send(command.userId().toString(), command.title(),
                    "You have a new notification in HeySaz. Open the app to view the details.");
        }
        return repository.find(id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationView> list(boolean unreadOnly, int limit) {
        return repository.listForUser(currentUserId(), unreadOnly, Math.clamp(limit, 1, 200));
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.unreadCountForUser(currentUserId());
    }

    @Override
    @Transactional
    public void markRead(UUID notificationId) {
        repository.find(notificationId).orElseThrow(() -> ApiException.notFound("Notification"));
        repository.markRead(notificationId);
    }

    @Override
    @Transactional
    public void markAllRead() {
        repository.markAllReadForUser(currentUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PreferenceView> getPreferences() {
        return repository.listPreferences(currentUserId());
    }

    @Override
    @Transactional
    public PreferenceView setPreference(Type type, Channel channel, boolean enabled) {
        repository.upsertPreference(TenantContext.requireOrgId(), currentUserId(),
                type.name(), channel.name(), enabled);
        return new PreferenceView(type.name(), channel.name(), enabled);
    }

    @Override
    @Transactional
    public int runReorderSweep() {
        int raised = 0;
        for (NotificationRepository.ReorderCandidate c : repository.findReorderCandidates()) {
            String referenceType = "StockBalance";
            String referenceId = c.shopId() + ":" + c.variantId();
            if (repository.hasUnreadOfType(Type.REORDER.name(), referenceType, referenceId)) {
                continue; // Already flagged and not yet acted on.
            }
            String title = "Reorder point reached: " + c.productName();
            String body = c.variantSku() + " has " + c.available().stripTrailingZeros().toPlainString()
                    + " available, at or below its reorder point of "
                    + c.reorderPoint().stripTrailingZeros().toPlainString() + ".";
            raise(new RaiseCommand(null, Type.REORDER, Severity.WARNING, title, body,
                    referenceType, referenceId));
            raised++;
        }
        return raised;
    }

    private boolean preferenceEnabled(UUID userId, Type type, Channel channel) {
        // Default: in-app on, email off, until the user says otherwise (FR-NOT-003).
        boolean defaultEnabled = channel == Channel.IN_APP;
        return repository.findPreference(userId, type.name(), channel.name()).orElse(defaultEnabled);
    }

    private UUID currentUserId() {
        Principal principal = TenantContext.requirePrincipal();
        if (principal.userId() == null) {
            throw ApiException.forbidden("This action requires a user principal");
        }
        return principal.userId();
    }
}
