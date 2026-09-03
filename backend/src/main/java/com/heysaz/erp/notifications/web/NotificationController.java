package com.heysaz.erp.notifications.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.notifications.api.NotificationService;

import jakarta.validation.constraints.NotNull;

/**
 * A user manages their own notifications, so these are gated on being authenticated rather
 * than on a specific capability — the service already scopes every read and write to the
 * current user (plus org-wide notices) under row-level security.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notificationService;

    NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    record SetPreferenceRequest(
            @NotNull NotificationService.Type type,
            @NotNull NotificationService.Channel channel,
            boolean enabled) {
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    List<NotificationService.NotificationView> list(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return notificationService.list(unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    long unreadCount() {
        return notificationService.unreadCount();
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    void markRead(@PathVariable UUID notificationId) {
        notificationService.markRead(notificationId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    void markAllRead() {
        notificationService.markAllRead();
    }

    @GetMapping("/preferences")
    @PreAuthorize("isAuthenticated()")
    List<NotificationService.PreferenceView> preferences() {
        return notificationService.getPreferences();
    }

    @PutMapping("/preferences")
    @PreAuthorize("isAuthenticated()")
    NotificationService.PreferenceView setPreference(@RequestBody SetPreferenceRequest request) {
        return notificationService.setPreference(request.type(), request.channel(), request.enabled());
    }
}
