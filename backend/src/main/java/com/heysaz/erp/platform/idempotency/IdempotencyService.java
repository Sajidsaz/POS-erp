package com.heysaz.erp.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

/**
 * FR-API-011 and FR-API-012.
 *
 * <p>The unique constraint on {@code (org_id, terminal_id, endpoint, idem_key)} does the
 * real work. {@code ON CONFLICT DO NOTHING} makes a concurrent duplicate <em>wait</em>
 * on the first transaction rather than racing it, and reports zero rows once that
 * transaction commits — so the second caller reads the original response instead of
 * re-executing. Catching a unique violation would not do: in PostgreSQL that aborts the
 * transaction, and the follow-up read could not run inside it.
 *
 * <p>Runs with {@code MANDATORY} propagation so the record and the business effect share
 * one transaction. That is FR-API-012, and it is what makes "a committed sale always has
 * a retrievable key" true rather than merely intended.
 */
@Service
public class IdempotencyService {

    /** Principals that are not a terminal still need a value for the NOT NULL scope column. */
    private static final String NO_TERMINAL = "-";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Duration retention;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper mapper,
                              @Value("${erp.idempotency.retention:PT24H}") Duration retention) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.retention = retention;
    }

    /** {@code replayed} tells the caller whether this is the original execution or a repeat. */
    public record Result<T>(T value, boolean replayed) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Result<T> execute(String endpoint, String idempotencyKey, Object request,
                                 Class<T> responseType, Supplier<T> action) {
        UUID orgId = TenantContext.requireOrgId();
        String terminal = TenantContext.principal()
                .map(Principal::terminalCode)
                .orElse(null);
        terminal = terminal == null ? NO_TERMINAL : terminal;
        String requestHash = sha256(toJson(request));

        int inserted = jdbc.sql("""
                INSERT INTO app.idempotency_record
                    (id, org_id, terminal_id, endpoint, idem_key, request_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (org_id, terminal_id, endpoint, idem_key) DO NOTHING
                """)
                .param(UUID.randomUUID())
                .param(orgId)
                .param(terminal)
                .param(endpoint)
                .param(idempotencyKey)
                .param(requestHash)
                .param(java.sql.Timestamp.from(Instant.now().plus(retention)))
                .update();

        if (inserted == 0) {
            return replay(orgId, terminal, endpoint, idempotencyKey, requestHash, responseType);
        }

        T value = action.get();
        jdbc.sql("""
                UPDATE app.idempotency_record
                SET response_status = 200, response_body = ?::jsonb
                WHERE org_id = ? AND terminal_id = ? AND endpoint = ? AND idem_key = ?
                """)
                .param(toJson(value))
                .param(orgId)
                .param(terminal)
                .param(endpoint)
                .param(idempotencyKey)
                .update();
        return new Result<>(value, false);
    }

    private <T> Result<T> replay(UUID orgId, String terminal, String endpoint, String key,
                                 String requestHash, Class<T> responseType) {
        record Stored(String requestHash, String responseBody) {
        }
        Optional<Stored> existing = jdbc.sql("""
                SELECT request_hash, response_body::text AS response_body
                FROM app.idempotency_record
                WHERE org_id = ? AND terminal_id = ? AND endpoint = ? AND idem_key = ?
                """)
                .param(orgId).param(terminal).param(endpoint).param(key)
                .query((rs, rowNum) -> new Stored(rs.getString("request_hash"), rs.getString("response_body")))
                .optional();

        Stored stored = existing.orElseThrow(() -> ApiException.conflict(
                "Idempotency record vanished between insert and read"));

        // Same key, different body: the caller changed its mind mid-retry, which is a
        // client bug worth surfacing rather than a duplicate worth absorbing.
        if (!stored.requestHash().equals(requestHash)) {
            throw ApiException.idempotencyMismatch();
        }
        if (stored.responseBody() == null) {
            throw ApiException.conflict("Original request is still in flight");
        }
        return new Result<>(fromJson(stored.responseBody(), responseType), true);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise idempotency payload", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot deserialise stored response", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
