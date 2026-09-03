package com.heysaz.erp.notifications.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * In-application notifications and the preferences that govern them (Section 13).
 *
 * <p>In-app delivery is always available (FR-NOT-002); email is delivered only where a
 * provider is configured, and only for a notification type the recipient has enabled for the
 * email channel (FR-NOT-003). {@link #raise} is the entry point other modules and scheduled
 * jobs use to emit a notification; the read side ({@link #list}, {@link #unreadCount}) is
 * scoped to the current user plus org-wide notices.
 */
public interface NotificationService {

    enum Severity { INFO, WARNING, CRITICAL }

    enum Channel { IN_APP, EMAIL }

    /** The types in FR-NOT-001; the string form is what preferences and rows store. */
    enum Type {
        LOW_STOCK, REORDER, SHIFT_VARIANCE, CREDIT_LIMIT, PURCHASE_ORDER_APPROVAL,
        RETURN_APPROVAL, TRANSFER_RECEIPT, BACKUP_FAILURE, SCHEDULED_REPORT
    }

    record RaiseCommand(
            /** Target user, or null for an org-wide notice. */
            UUID userId,
            @NotNull Type type,
            @NotNull Severity severity,
            @NotBlank String title,
            String body,
            String referenceType,
            String referenceId) {
    }

    record NotificationView(
            UUID id,
            UUID userId,
            String type,
            String severity,
            String title,
            String body,
            String referenceType,
            String referenceId,
            Instant readAt,
            Instant createdAt) {
    }

    record PreferenceView(String type, String channel, boolean enabled) {
    }

    /** Emits a notification (in-app now, email when a provider is configured and enabled). */
    NotificationView raise(RaiseCommand command);

    List<NotificationView> list(boolean unreadOnly, int limit);

    long unreadCount();

    void markRead(UUID notificationId);

    void markAllRead();

    List<PreferenceView> getPreferences();

    PreferenceView setPreference(Type type, Channel channel, boolean enabled);

    /**
     * FR-INV-013: raises a REORDER notification for each variant/shop whose available stock
     * has fallen to or below its reorder point, skipping any that already have an unread one.
     * Returns how many new alerts were raised. Runs for the current tenant.
     */
    int runReorderSweep();
}
