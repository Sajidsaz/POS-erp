package com.heysaz.erp.organization.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shops and terminals — the organization module's public surface.
 *
 * <p>FR-ORG-008: a warehouse is a shop with {@code sellingEnabled = false}, not a separate
 * entity. Stock, transfers and counts then have one code path instead of two that drift.
 */
public interface OrganizationService {

    record CreateShopCommand(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String name,
            /** Decision D5: the leading segment of every document number this shop issues. */
            @NotBlank @Size(max = 12) @Pattern(regexp = "[A-Z0-9-]+",
                    message = "must be upper-case letters, digits or hyphens")
            String documentPrefix,
            String addressLine1,
            String city,
            String phone,
            String timezone,
            String taxRegistrationNo,
            boolean sellingEnabled) {
    }

    record ShopView(UUID id, String code, String name, String documentPrefix, String city,
                    String timezone, String currency, boolean sellingEnabled, boolean active) {
    }

    record CreateTerminalCommand(
            UUID shopId,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String name,
            String printerType,
            String printerAddress,
            Integer paperWidthMm,
            Boolean cashDrawerEnabled,
            Boolean customerDisplayEnabled) {
    }

    record TerminalView(UUID id, UUID shopId, String code, String name, String printerType,
                        int paperWidthMm, boolean cashDrawerEnabled, boolean disabled) {
    }

    ShopView createShop(CreateShopCommand command, String idempotencyKey);

    List<ShopView> listShops();

    ShopView getShop(UUID id);

    TerminalView createTerminal(CreateTerminalCommand command, String idempotencyKey);

    List<TerminalView> listTerminals(UUID shopId);

    /**
     * FR-TERM-004. Disabling a terminal must also stop the credential it is holding, or
     * the terminal stays usable until its token expires — which is not what "disable a
     * compromised terminal" means.
     */
    void disableTerminal(UUID id, String reason);
}
