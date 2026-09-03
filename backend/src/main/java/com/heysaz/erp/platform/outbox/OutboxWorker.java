package com.heysaz.erp.platform.outbox;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the outbox. FR-API-003 requires bounded exponential backoff and a visible
 * dead-letter state for deliveries that exhaust their attempts.
 *
 * <p>This is the one component that reads across tenants, so it runs on the elevated
 * connection rather than the application's RLS-bound one — the explicit escape hatch
 * named in Appendix D decision D1, rather than a weakening of the policy. Nothing that
 * serves a request may use this data source.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets several application instances drain the same
 * table without coordinating and without double-delivering.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 12;

    private final JdbcClient elevatedJdbc;
    private final TransactionTemplate elevatedTx;
    private final OutboxDispatcher dispatcher;

    public OutboxWorker(@Qualifier("elevatedJdbcClient") JdbcClient elevatedJdbc,
                        @Qualifier("elevatedTransactionTemplate") TransactionTemplate elevatedTx,
                        OutboxDispatcher dispatcher) {
        this.elevatedJdbc = elevatedJdbc;
        this.elevatedTx = elevatedTx;
        this.dispatcher = dispatcher;
    }

    public record PendingEvent(UUID id, UUID orgId, String eventType, String aggregateType,
                               String aggregateId, String payload, int attempts) {
    }

    @Scheduled(fixedDelayString = "${erp.outbox.poll-interval-ms:1000}")
    public void drain() {
        try {
            elevatedTx.executeWithoutResult(status -> {
                for (PendingEvent event : claimBatch()) {
                    dispatch(event);
                }
            });
        } catch (Exception e) {
            log.error("Outbox drain cycle failed", e);
        }
    }

    private List<PendingEvent> claimBatch() {
        return elevatedJdbc.sql("""
                SELECT id, org_id, event_type, aggregate_type, aggregate_id,
                       payload::text AS payload, attempts
                FROM app.outbox_event
                WHERE published_at IS NULL
                  AND next_attempt_at <= now()
                  AND attempts < ?
                ORDER BY occurred_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """)
                .param(MAX_ATTEMPTS)
                .param(BATCH_SIZE)
                .query((rs, rowNum) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();
    }

    private void dispatch(PendingEvent event) {
        try {
            dispatcher.dispatch(event);
            elevatedJdbc.sql("UPDATE app.outbox_event SET published_at = now(), last_error = NULL WHERE id = ?")
                    .param(event.id())
                    .update();
        } catch (Exception e) {
            log.warn("Outbox delivery failed for {} (attempt {})", event.id(), event.attempts() + 1, e);
            // Backoff doubles per attempt and is capped, so a permanently broken consumer
            // costs one retry every few minutes rather than a hot loop.
            elevatedJdbc.sql("""
                    UPDATE app.outbox_event
                    SET attempts = attempts + 1,
                        last_error = ?,
                        next_attempt_at = now() + (interval '1 second' * power(2, least(attempts, 8)))
                    WHERE id = ?
                    """)
                    .param(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .param(event.id())
                    .update();
        }
    }

    /**
     * Where an event goes once it is safely committed. M5 supplies the signed webhook
     * implementation required by FR-API-002; until subscribers exist there is nothing
     * to deliver to, so the default records and drops.
     */
    public interface OutboxDispatcher {
        void dispatch(PendingEvent event);
    }

    @Component
    static class LoggingOutboxDispatcher implements OutboxDispatcher {
        @Override
        public void dispatch(PendingEvent event) {
            log.info("outbox event {} type={} aggregate={}/{} org={}",
                    event.id(), event.eventType(), event.aggregateType(),
                    event.aggregateId(), event.orgId());
        }
    }
}
