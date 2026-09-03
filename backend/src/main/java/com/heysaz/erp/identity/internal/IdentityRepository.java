package com.heysaz.erp.identity.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.identity.api.GrantLevel;
import com.heysaz.erp.identity.api.IdentityService;

@Repository
class IdentityRepository {

    private final JdbcClient jdbc;

    IdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean rolesProvisioned(UUID orgId) {
        return jdbc.sql("SELECT count(*) FROM app.role WHERE is_system").query(Long.class).single() > 0;
    }

    /**
     * Copies the platform role templates into this organization in one statement, so the
     * seeding cannot half-apply. INSERT ... SELECT keeps the matrix in the database rather
     * than round-tripping 114 grants through the application.
     */
    void provisionFromTemplates(UUID orgId) {
        jdbc.sql("""
                INSERT INTO app.role (id, org_id, code, name, is_system)
                SELECT gen_random_uuid(), ?, t.code, t.name, true
                FROM platform.role_template t
                ORDER BY t.sort_order
                """)
                .param(orgId)
                .update();

        jdbc.sql("""
                INSERT INTO app.role_permission (org_id, role_id, permission_key, level)
                SELECT r.org_id, r.id, tp.permission_key, tp.level
                FROM app.role r
                JOIN platform.role_template_permission tp ON tp.role_code = r.code
                WHERE r.is_system
                """)
                .update();
    }

    /**
     * One query for every grant the user holds. A user with several roles gets the union
     * of their levels; {@link GrantLevel#expandAll} then reduces that to authorities.
     */
    Map<String, Set<GrantLevel>> grantsForUser(UUID userId) {
        Map<String, Set<GrantLevel>> grants = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rp.permission_key, rp.level::text AS level
                FROM app.user_role ur
                JOIN app.role_permission rp ON rp.role_id = ur.role_id
                WHERE ur.user_id = ?
                """)
                .param(userId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("permission_key"), GrantLevel.valueOf(rs.getString("level"))))
                .list()
                .forEach(entry -> grants
                        .computeIfAbsent(entry.getKey(), k -> new java.util.LinkedHashSet<>())
                        .add(entry.getValue()));
        return grants;
    }

    Set<UUID> shopScopeForUser(UUID userId) {
        return Set.copyOf(jdbc.sql("SELECT shop_id FROM app.user_shop WHERE user_id = ?")
                .param(userId)
                .query(UUID.class)
                .list());
    }

    List<IdentityService.RoleView> listRoles() {
        List<IdentityService.RoleView> roles = new ArrayList<>();
        jdbc.sql("SELECT id, code, name, is_system FROM app.role ORDER BY code")
                .query((rs, rowNum) -> new Object[] {
                        rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBoolean("is_system") })
                .list()
                .forEach(row -> {
                    UUID id = (UUID) row[0];
                    roles.add(new IdentityService.RoleView(id, (String) row[1], (String) row[2],
                            (Boolean) row[3], grantsForRole(id)));
                });
        return roles;
    }

    List<IdentityService.GrantView> grantsForRole(UUID roleId) {
        return jdbc.sql("""
                SELECT permission_key, level::text AS level
                FROM app.role_permission WHERE role_id = ? ORDER BY permission_key
                """)
                .param(roleId)
                .query((rs, rowNum) -> new IdentityService.GrantView(
                        rs.getString("permission_key"), GrantLevel.valueOf(rs.getString("level"))))
                .list();
    }

    boolean roleExists(UUID roleId) {
        return jdbc.sql("SELECT count(*) FROM app.role WHERE id = ?")
                .param(roleId).query(Long.class).single() > 0;
    }

    void assignRole(UUID orgId, UUID userId, UUID roleId, UUID grantedBy) {
        jdbc.sql("""
                INSERT INTO app.user_role (org_id, user_id, role_id, granted_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, role_id) DO NOTHING
                """)
                .param(orgId).param(userId).param(roleId).param(grantedBy)
                .update();
    }

    void revokeRole(UUID userId, UUID roleId) {
        jdbc.sql("DELETE FROM app.user_role WHERE user_id = ? AND role_id = ?")
                .param(userId).param(roleId)
                .update();
    }

    void replaceShopScope(UUID orgId, UUID userId, Set<UUID> shopIds) {
        jdbc.sql("DELETE FROM app.user_shop WHERE user_id = ?").param(userId).update();
        for (UUID shopId : shopIds) {
            jdbc.sql("INSERT INTO app.user_shop (org_id, user_id, shop_id) VALUES (?, ?, ?)")
                    .param(orgId).param(userId).param(shopId)
                    .update();
        }
    }
}
