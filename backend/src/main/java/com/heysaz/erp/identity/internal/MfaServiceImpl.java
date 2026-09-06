package com.heysaz.erp.identity.internal;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.heysaz.erp.identity.api.MfaService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.security.TotpService;

/**
 * Reads and writes MFA state on the elevated connection, for the same reason authentication
 * does: it is resolved before a tenant context exists. Multi-statement operations run through
 * {@code elevatedTransactionTemplate} so they commit or roll back as a unit on that same
 * connection — a plain {@code @Transactional} here would bind the RLS-bound pool instead and
 * leave these writes in autocommit. Recovery codes are stored only as bcrypt hashes; the
 * plaintext is returned once and never persisted.
 */
@Service
class MfaServiceImpl implements MfaService {

    private static final String ISSUER = "HeySaz ERP";
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int TOTP_WINDOW = 1;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final JdbcClient elevatedJdbc;
    private final TransactionTemplate elevatedTx;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totp;

    MfaServiceImpl(@Qualifier("elevatedJdbcClient") JdbcClient elevatedJdbc,
                   @Qualifier("elevatedTransactionTemplate") TransactionTemplate elevatedTx,
                   PasswordEncoder passwordEncoder, TotpService totp) {
        this.elevatedJdbc = elevatedJdbc;
        this.elevatedTx = elevatedTx;
        this.passwordEncoder = passwordEncoder;
        this.totp = totp;
    }

    private record MfaRow(String secret, boolean enabled) {
    }

    private Optional<MfaRow> findMfa(UUID userId) {
        return elevatedJdbc.sql("SELECT secret, enabled FROM platform.user_mfa WHERE user_id = ?")
                .param(userId)
                .query((rs, n) -> new MfaRow(rs.getString("secret"), rs.getBoolean("enabled")))
                .optional();
    }

    @Override
    public EnrollmentView beginEnrollment(UUID userId, String accountLabel) {
        String secret = totp.generateSecret();
        elevatedJdbc.sql("""
                INSERT INTO platform.user_mfa (user_id, secret, enabled, confirmed_at)
                VALUES (?, ?, false, NULL)
                ON CONFLICT (user_id)
                DO UPDATE SET secret = EXCLUDED.secret, enabled = false,
                              confirmed_at = NULL, updated_at = now()
                """)
                .param(userId).param(secret)
                .update();
        String label = accountLabel == null || accountLabel.isBlank() ? userId.toString() : accountLabel;
        return new EnrollmentView(secret, totp.provisioningUri(ISSUER, label, secret));
    }

    @Override
    public RecoveryCodesView confirmEnrollment(UUID userId, String code) {
        return elevatedTx.execute(status -> {
            MfaRow mfa = findMfa(userId)
                    .orElseThrow(() -> ApiException.conflict("No MFA enrollment is in progress"));
            if (!totp.verify(mfa.secret(), code, System.currentTimeMillis(), TOTP_WINDOW)) {
                throw ApiException.forbidden("The verification code is not valid");
            }
            elevatedJdbc.sql("""
                    UPDATE platform.user_mfa SET enabled = true, confirmed_at = now(), updated_at = now()
                    WHERE user_id = ?
                    """)
                    .param(userId)
                    .update();
            return new RecoveryCodesView(replaceRecoveryCodes(userId));
        });
    }

    @Override
    public RecoveryCodesView regenerateRecoveryCodes(UUID userId, String code) {
        return elevatedTx.execute(status -> {
            requireVerified(userId, code);
            return new RecoveryCodesView(replaceRecoveryCodes(userId));
        });
    }

    @Override
    public void disable(UUID userId, String code) {
        elevatedTx.executeWithoutResult(status -> {
            requireVerified(userId, code);
            // ON DELETE CASCADE removes the recovery codes with the row.
            elevatedJdbc.sql("DELETE FROM platform.user_mfa WHERE user_id = ?").param(userId).update();
        });
    }

    @Override
    public MfaStatusView status(UUID userId) {
        Optional<MfaRow> mfa = findMfa(userId);
        boolean enabled = mfa.map(MfaRow::enabled).orElse(false);
        boolean pending = mfa.isPresent() && !enabled;
        int unused = enabled ? countUnusedRecoveryCodes(userId) : 0;
        return new MfaStatusView(enabled, pending, unused);
    }

    @Override
    public boolean isEnabled(UUID userId) {
        return findMfa(userId).map(MfaRow::enabled).orElse(false);
    }

    @Override
    public boolean verify(UUID userId, String code) {
        Optional<MfaRow> mfa = findMfa(userId);
        if (mfa.isEmpty() || !mfa.get().enabled() || code == null || code.isBlank()) {
            return false;
        }
        if (totp.verify(mfa.get().secret(), code, System.currentTimeMillis(), TOTP_WINDOW)) {
            return true;
        }
        return consumeRecoveryCode(userId, code.trim());
    }

    @Override
    public void setOrgMfaRequired(UUID orgId, boolean required) {
        elevatedJdbc.sql("UPDATE platform.organization SET mfa_required_privileged = ? WHERE id = ?")
                .param(required).param(orgId)
                .update();
    }

    @Override
    public boolean isOrgMfaRequired(UUID orgId) {
        if (orgId == null) {
            return false;
        }
        return elevatedJdbc.sql("SELECT mfa_required_privileged FROM platform.organization WHERE id = ?")
                .param(orgId).query(Boolean.class).optional().orElse(false);
    }

    // ------------------------------------------------------------------ Helpers

    private void requireVerified(UUID userId, String code) {
        MfaRow mfa = findMfa(userId).orElseThrow(() -> ApiException.conflict("MFA is not enabled"));
        if (!mfa.enabled()) {
            throw ApiException.conflict("MFA is not enabled");
        }
        if (!verify(userId, code)) {
            throw ApiException.forbidden("The verification code is not valid");
        }
    }

    private List<String> replaceRecoveryCodes(UUID userId) {
        elevatedJdbc.sql("DELETE FROM platform.mfa_recovery_code WHERE user_id = ?")
                .param(userId).update();
        List<String> plaintext = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = newRecoveryCode();
            plaintext.add(code);
            elevatedJdbc.sql("""
                    INSERT INTO platform.mfa_recovery_code (id, user_id, code_hash)
                    VALUES (?, ?, ?)
                    """)
                    .param(UUID.randomUUID()).param(userId).param(passwordEncoder.encode(code))
                    .update();
        }
        return plaintext;
    }

    private boolean consumeRecoveryCode(UUID userId, String code) {
        record Row(UUID id, String hash) {
        }
        List<Row> unused = elevatedJdbc.sql("""
                SELECT id, code_hash FROM platform.mfa_recovery_code
                WHERE user_id = ? AND used_at IS NULL
                """)
                .param(userId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("code_hash")))
                .list();
        for (Row row : unused) {
            if (passwordEncoder.matches(code, row.hash())) {
                int updated = elevatedJdbc.sql("""
                        UPDATE platform.mfa_recovery_code SET used_at = now()
                        WHERE id = ? AND used_at IS NULL
                        """)
                        .param(row.id())
                        .update();
                return updated == 1; // Lost a race for the same code? Then it was already used.
            }
        }
        return false;
    }

    private int countUnusedRecoveryCodes(UUID userId) {
        return elevatedJdbc.sql("""
                SELECT count(*) FROM platform.mfa_recovery_code WHERE user_id = ? AND used_at IS NULL
                """)
                .param(userId).query(Integer.class).single();
    }

    private static String newRecoveryCode() {
        byte[] bytes = new byte[5];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes); // 10 chars
        return hex.substring(0, 5) + "-" + hex.substring(5);
    }
}
