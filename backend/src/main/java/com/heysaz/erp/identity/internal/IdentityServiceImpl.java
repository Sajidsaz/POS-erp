package com.heysaz.erp.identity.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.heysaz.erp.identity.api.GrantLevel;
import com.heysaz.erp.identity.api.IdentityService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.security.CredentialRevocationService;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class IdentityServiceImpl implements IdentityService {

    private final IdentityRepository repository;
    private final AuditService audit;
    private final CredentialRevocationService revocation;
    private final TransactionTemplate transactionTemplate;

    IdentityServiceImpl(IdentityRepository repository, AuditService audit,
                        CredentialRevocationService revocation,
                        TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.audit = audit;
        this.revocation = revocation;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void provisionRoles(UUID orgId) {
        // Runs before the organization has any users, so it binds its own tenant context
        // rather than borrowing the elevated pool.
        TenantContext.runAsOrg(orgId, () -> transactionTemplate.execute(status -> {
            if (!repository.rolesProvisioned(orgId)) {
                repository.provisionFromTemplates(orgId);
            }
            return null;
        }));
    }

    @Override
    public Resolution resolve(UUID userId, UUID orgId) {
        return TenantContext.runAsOrg(orgId, () -> transactionTemplate.execute(status -> {
            Set<String> authorities = GrantLevel.expandAll(repository.grantsForUser(userId));
            return new Resolution(authorities, repository.shopScopeForUser(userId));
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return repository.listRoles();
    }

    @Override
    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        if (!repository.roleExists(roleId)) {
            // RLS already hid a role belonging to another tenant, so this is a 404 and
            // discloses nothing about whether it exists elsewhere.
            throw ApiException.notFound("Role");
        }
        UUID orgId = TenantContext.requireOrgId();
        UUID actor = TenantContext.requirePrincipal().userId();

        List<GrantView> priorGrants = repository.grantsForRole(roleId);
        repository.assignRole(orgId, userId, roleId, actor);

        // FR-USER-004
        audit.record("user.role_assigned", "User", userId.toString(),
                null, new RoleChange(roleId, priorGrants), AuditService.Outcome.SUCCESS);
        // The new permissions apply on the user's next request, not their next login.
        revocation.revokeUserCredentials(userId);
    }

    @Override
    @Transactional
    public void revokeRole(UUID userId, UUID roleId) {
        repository.revokeRole(userId, roleId);
        audit.record("user.role_revoked", "User", userId.toString(),
                new RoleChange(roleId, List.of()), null, AuditService.Outcome.SUCCESS);
        revocation.revokeUserCredentials(userId);
    }

    @Override
    @Transactional
    public void setShopScope(UUID userId, Set<UUID> shopIds) {
        UUID orgId = TenantContext.requireOrgId();
        Set<UUID> prior = repository.shopScopeForUser(userId);
        repository.replaceShopScope(orgId, userId, shopIds);

        audit.record("user.shop_scope_changed", "User", userId.toString(),
                prior, shopIds, AuditService.Outcome.SUCCESS);
        revocation.revokeUserCredentials(userId);
    }

    private record RoleChange(UUID roleId, List<GrantView> grants) {
    }
}
