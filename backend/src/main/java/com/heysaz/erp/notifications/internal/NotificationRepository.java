package com.heysaz.erp.notifications.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.notifications.api.NotificationService.NotificationView;
import com.heysaz.erp.notifications.api.NotificationService.PreferenceView;

@Repository
class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID orgId, UUID userId, String type, String severity, String title,
                String body, String referenceType, String referenceId) {
        jdbc.sql("""
                INSERT INTO app.notification
                    (id, org_id, user_id, type, severity, title, body, reference_type, reference_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(userId).param(type).param(severity).param(title)
                .param(body).param(referenceType).param(referenceId)
                .update();
    }

    Optional<NotificationView> find(UUID id) {
        return jdbc.sql("""
                SELECT id, user_id, type, severity, title, body, reference_type, reference_id,
                       read_at, created_at
                FROM app.notification WHERE id = ?
                """)
                .param(id)
                .query(this::map)
                .optional();
    }

    List<NotificationView> listForUser(UUID userId, boolean unreadOnly, int limit) {
        String unread = unreadOnly ? " AND read_at IS NULL" : "";
        return jdbc.sql("""
                SELECT id, user_id, type, severity, title, body, reference_type, reference_id,
                       read_at, created_at
                FROM app.notification
                WHERE (user_id = ? OR user_id IS NULL)%s
                ORDER BY created_at DESC
                LIMIT ?
                """.formatted(unread))
                .param(userId).param(limit)
                .query(this::map)
                .list();
    }

    long unreadCountForUser(UUID userId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.notification
                WHERE (user_id = ? OR user_id IS NULL) AND read_at IS NULL
                """)
                .param(userId).query(Long.class).single();
    }

    void markRead(UUID id) {
        jdbc.sql("UPDATE app.notification SET read_at = now() WHERE id = ? AND read_at IS NULL")
                .param(id).update();
    }

    void markAllReadForUser(UUID userId) {
        jdbc.sql("""
                UPDATE app.notification SET read_at = now()
                WHERE (user_id = ? OR user_id IS NULL) AND read_at IS NULL
                """)
                .param(userId).update();
    }

    boolean hasUnreadOfType(String type, String referenceType, String referenceId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.notification
                WHERE type = ? AND reference_type = ? AND reference_id = ? AND read_at IS NULL
                """)
                .param(type).param(referenceType).param(referenceId)
                .query(Long.class).single() > 0;
    }

    // --------------------------------------------------------------- Preferences

    Optional<Boolean> findPreference(UUID userId, String type, String channel) {
        return jdbc.sql("""
                SELECT enabled FROM app.notification_preference
                WHERE user_id = ? AND type = ? AND channel = ?
                """)
                .param(userId).param(type).param(channel)
                .query(Boolean.class)
                .optional();
    }

    void upsertPreference(UUID orgId, UUID userId, String type, String channel, boolean enabled) {
        jdbc.sql("""
                INSERT INTO app.notification_preference (id, org_id, user_id, type, channel, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (org_id, user_id, type, channel) DO UPDATE SET enabled = EXCLUDED.enabled
                """)
                .param(UUID.randomUUID()).param(orgId).param(userId).param(type).param(channel)
                .param(enabled)
                .update();
    }

    List<PreferenceView> listPreferences(UUID userId) {
        return jdbc.sql("""
                SELECT type, channel, enabled FROM app.notification_preference
                WHERE user_id = ? ORDER BY type, channel
                """)
                .param(userId)
                .query((rs, n) -> new PreferenceView(
                        rs.getString("type"), rs.getString("channel"), rs.getBoolean("enabled")))
                .list();
    }

    // --------------------------------------------------------------- Reorder sweep

    record ReorderCandidate(UUID shopId, UUID variantId, String variantSku, String productName,
                            BigDecimal available, BigDecimal reorderPoint) {
    }

    List<ReorderCandidate> findReorderCandidates() {
        return jdbc.sql("""
                SELECT b.shop_id, b.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       (b.quantity_on_hand - b.quantity_reserved) AS available, b.reorder_point
                FROM app.stock_balance b
                JOIN app.variant v ON v.id = b.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE b.reorder_point IS NOT NULL
                  AND b.reorder_point > 0
                  AND (b.quantity_on_hand - b.quantity_reserved) <= b.reorder_point
                """)
                .query((rs, n) -> new ReorderCandidate(
                        rs.getObject("shop_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("available"),
                        rs.getBigDecimal("reorder_point")))
                .list();
    }

    private NotificationView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new NotificationView(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("type"),
                rs.getString("severity"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
