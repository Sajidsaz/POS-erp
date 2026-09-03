package com.heysaz.erp.identity.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.identity.api.IdentityService;

@RestController
@RequestMapping("/api/v1")
class RoleController {

    private final IdentityService identityService;

    RoleController(IdentityService identityService) {
        this.identityService = identityService;
    }

    record ShopScopeRequest(Set<UUID> shopIds) {
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('users_roles.read')")
    List<IdentityService.RoleView> listRoles() {
        return identityService.listRoles();
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('users_roles.write')")
    void assignRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        identityService.assignRole(userId, roleId);
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('users_roles.write')")
    void revokeRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        identityService.revokeRole(userId, roleId);
    }

    /** FR-ORG-003. An empty set leaves the user able to see no shop-scoped data at all. */
    @PutMapping("/users/{userId}/shop-scope")
    @PreAuthorize("hasAuthority('users_roles.write')")
    void setShopScope(@PathVariable UUID userId, @RequestBody ShopScopeRequest request) {
        identityService.setShopScope(userId,
                request.shopIds() == null ? Set.of() : request.shopIds());
    }
}
