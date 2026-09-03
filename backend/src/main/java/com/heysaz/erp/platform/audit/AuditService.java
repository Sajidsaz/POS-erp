package com.heysaz.erp.platform.audit;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

/**
 * Writes the audit records required by AUD-001 and AUD-002.
 *
 * <p>Deliberately joins the caller's transaction rather than opening its own: an audit
 * row for an action that then rolled back would be a lie, and AUD-003 wants the record
 * and the effect to share a fate.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public enum Outcome { SUCCESS, DENIED, FAILED }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, String entityId,
                       Object priorValue, Object newValue, Outcome outcome) {
        Principal principal = TenantContext.principal().orElse(null);
        jdbc.sql("""
                INSERT INTO app.audit_log
                    (id, org_id, actor_user_id, actor_type, action, entity_type, entity_id,
                     prior_value, new_value, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """)
                .param(UUID.randomUUID())
                .param(TenantContext.requireOrgId())
                .param(principal == null ? null : principal.userId())
                .param(principal == null ? "SYSTEM" : principal.type().name())
                .param(action)
                .param(entityType)
                .param(entityId)
                .param(toJson(priorValue))
                .param(toJson(newValue))
                .param(outcome.name())
                .update();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise audit payload", e);
        }
    }
}
