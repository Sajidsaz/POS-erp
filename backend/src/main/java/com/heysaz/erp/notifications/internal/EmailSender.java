package com.heysaz.erp.notifications.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The email delivery seam (FR-NOT-002: email "where a provider is configured under Section
 * 18"). No provider ships yet, so the default implementation reports itself unconfigured and
 * the notification service falls back to in-app only. A real SMTP/API sender replaces this
 * bean without the service changing.
 */
public interface EmailSender {

    boolean isConfigured();

    void send(String toUserId, String subject, String body);

    @Component
    class LoggingEmailSender implements EmailSender {

        private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public void send(String toUserId, String subject, String body) {
            log.info("email (no provider configured) to user {}: {}", toUserId, subject);
        }
    }
}
