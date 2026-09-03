package com.heysaz.erp.platform.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.platform.tenant.TenantContext;

/**
 * FR-API-005: an event is written to the outbox in the same transaction as the business
 * change that produced it, and delivered later by {@link OutboxWorker}.
 *
 * <p>{@code Propagation.MANDATORY} is the whole point. Publishing outside a transaction
 * would reintroduce the failure the outbox exists to remove — a committed sale with no
 * event, or an event for a sale that rolled back — so it is a programming error rather
 * than something to paper over. Appendix B invariant B13 is the assertion that this
 * holds; the annotation is what makes it true by construction.
 */
@Service
public class OutboxPublisher {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutboxPublisher(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String eventType, String aggregateType, String aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app.outbox_event
                    (id, org_id, event_type, aggregate_type, aggregate_id, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """)
                .param(eventId)
                .param(TenantContext.requireOrgId())
                .param(eventType)
                .param(aggregateType)
                .param(aggregateId)
                .param(toJson(payload))
                .update();
        return eventId;
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise event payload", e);
        }
    }
}
