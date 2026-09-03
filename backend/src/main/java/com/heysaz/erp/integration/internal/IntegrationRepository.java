package com.heysaz.erp.integration.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.integration.api.IntegrationService.DeliveryView;
import com.heysaz.erp.integration.api.IntegrationService.SubscriptionView;

@Repository
class IntegrationRepository {

    private final JdbcClient jdbc;

    IntegrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertSubscription(UUID id, UUID orgId, String url, String secret, List<String> eventTypes,
                            String description, UUID createdBy) {
        jdbc.sql("""
                INSERT INTO app.webhook_subscription
                    (id, org_id, url, secret, event_types, description, created_by)
                VALUES (?, ?, ?, ?, ?::text[], ?, ?)
                """)
                .param(id).param(orgId).param(url).param(secret)
                .param(toArrayLiteral(eventTypes)).param(description).param(createdBy)
                .update();
    }

    Optional<SubscriptionView> findSubscription(UUID id) {
        return jdbc.sql("""
                SELECT id, url, event_types, description, active, created_at
                FROM app.webhook_subscription WHERE id = ?
                """)
                .param(id)
                .query(this::mapSubscription)
                .optional();
    }

    List<SubscriptionView> listSubscriptions() {
        return jdbc.sql("""
                SELECT id, url, event_types, description, active, created_at
                FROM app.webhook_subscription ORDER BY created_at DESC
                """)
                .query(this::mapSubscription)
                .list();
    }

    void setActive(UUID id, boolean active) {
        jdbc.sql("UPDATE app.webhook_subscription SET active = ? WHERE id = ?")
                .param(active).param(id)
                .update();
    }

    int deleteSubscription(UUID id) {
        return jdbc.sql("DELETE FROM app.webhook_subscription WHERE id = ?").param(id).update();
    }

    List<DeliveryView> listDeliveries(UUID subscriptionId, int limit) {
        return jdbc.sql("""
                SELECT id, subscription_id, event_type, url, status, attempts, response_status,
                       last_error, created_at, delivered_at
                FROM app.webhook_delivery
                WHERE subscription_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """)
                .param(subscriptionId).param(limit)
                .query((rs, rowNum) -> new DeliveryView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subscription_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("url"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("delivered_at") == null
                                ? null : rs.getTimestamp("delivered_at").toInstant()))
                .list();
    }

    private SubscriptionView mapSubscription(ResultSet rs, int rowNum) throws SQLException {
        return new SubscriptionView(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                readTextArray(rs.getArray("event_types")),
                rs.getString("description"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    /** A PostgreSQL array literal with each element double-quoted, e.g. {@code {"a","b"}}. */
    private static String toArrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
