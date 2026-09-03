package com.heysaz.erp.platform.sequence;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.TenantContext;

/**
 * Appendix D decision D5: gapless sequential document numbering per shop, per fiscal year.
 *
 * <p>A shared kernel service, not an inventory one: the same series discipline covers
 * SALE and RETURN (POS), TRANSFER (inventory) and PURCHASE_ORDER / GOODS_RECEIPT
 * (purchasing). Living in the platform keeps one implementation of the invariant rather
 * than one per module that consumes it.
 *
 * <p>Locks the sequence row {@code FOR UPDATE} within the caller's business transaction,
 * so the number is consumed strictly on commit — that is what keeps the series gapless.
 * {@code MANDATORY} propagation makes the missing-transaction case a loud failure rather
 * than a silently non-atomic allocation.
 */
@Service
public class DocumentSequenceService {

    private final JdbcClient jdbc;

    public DocumentSequenceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextDocumentNumber(UUID shopId, String documentType) {
        UUID orgId = TenantContext.requireOrgId();
        int fiscalYear = LocalDate.now().getYear() % 100; // e.g. 26 for 2026

        String prefix = jdbc.sql("SELECT document_prefix FROM app.shop WHERE id = ?")
                .param(shopId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Shop"));

        // Ensure row exists
        jdbc.sql("""
                INSERT INTO app.document_sequence (id, org_id, shop_id, document_type, fiscal_year, next_value)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (org_id, shop_id, document_type, fiscal_year) DO NOTHING
                """)
                .param(UUID.randomUUID())
                .param(orgId)
                .param(shopId)
                .param(documentType)
                .param(fiscalYear)
                .update();

        // Lock FOR UPDATE and increment
        Long current = jdbc.sql("""
                SELECT next_value FROM app.document_sequence
                WHERE org_id = ? AND shop_id = ? AND document_type = ? AND fiscal_year = ?
                FOR UPDATE
                """)
                .param(orgId)
                .param(shopId)
                .param(documentType)
                .param(fiscalYear)
                .query(Long.class)
                .single();

        jdbc.sql("""
                UPDATE app.document_sequence
                SET next_value = next_value + 1
                WHERE org_id = ? AND shop_id = ? AND document_type = ? AND fiscal_year = ?
                """)
                .param(orgId)
                .param(shopId)
                .param(documentType)
                .param(fiscalYear)
                .update();

        return "%s-%02d-%06d".formatted(prefix, fiscalYear, current);
    }
}
