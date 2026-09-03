package com.heysaz.erp.organization.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.organization.api.OrganizationService.ShopView;
import com.heysaz.erp.organization.api.OrganizationService.TerminalView;

/** No org predicate anywhere: the V4 policies do that filtering inside PostgreSQL. */
@Repository
class OrganizationRepository {

    private final JdbcClient jdbc;

    OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- shops

    void insertShop(UUID id, UUID orgId, String code, String name, String documentPrefix,
                    String addressLine1, String city, String phone, String timezone,
                    String taxRegistrationNo, boolean sellingEnabled) {
        jdbc.sql("""
                INSERT INTO app.shop
                    (id, org_id, code, name, document_prefix, address_line1, city, phone,
                     timezone, tax_registration_no, selling_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, 'Asia/Colombo'), ?, ?)
                """)
                .param(id).param(orgId).param(code).param(name).param(documentPrefix)
                .param(addressLine1).param(city).param(phone).param(timezone)
                .param(taxRegistrationNo).param(sellingEnabled)
                .update();
    }

    Optional<ShopView> findShop(UUID id) {
        return jdbc.sql("SELECT * FROM app.shop WHERE id = ?")
                .param(id).query(this::mapShop).optional();
    }

    Optional<ShopView> findShopByCode(String code) {
        return jdbc.sql("SELECT * FROM app.shop WHERE code = ?")
                .param(code).query(this::mapShop).optional();
    }

    Optional<ShopView> findShopByPrefix(String prefix) {
        return jdbc.sql("SELECT * FROM app.shop WHERE document_prefix = ?")
                .param(prefix).query(this::mapShop).optional();
    }

    List<ShopView> findShops() {
        return jdbc.sql("SELECT * FROM app.shop WHERE active ORDER BY code")
                .query(this::mapShop).list();
    }

    // --------------------------------------------------------------- terminals

    void insertTerminal(UUID id, UUID orgId, UUID shopId, String code, String name,
                        String printerType, String printerAddress, int paperWidthMm,
                        boolean cashDrawer, boolean customerDisplay) {
        jdbc.sql("""
                INSERT INTO app.terminal
                    (id, org_id, shop_id, code, name, printer_type, printer_address,
                     paper_width_mm, cash_drawer_enabled, customer_display_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(shopId).param(code).param(name)
                .param(printerType).param(printerAddress).param(paperWidthMm)
                .param(cashDrawer).param(customerDisplay)
                .update();
    }

    Optional<TerminalView> findTerminal(UUID id) {
        return jdbc.sql("SELECT * FROM app.terminal WHERE id = ?")
                .param(id).query(this::mapTerminal).optional();
    }

    Optional<TerminalView> findTerminalByCode(String code) {
        return jdbc.sql("SELECT * FROM app.terminal WHERE code = ?")
                .param(code).query(this::mapTerminal).optional();
    }

    List<TerminalView> findTerminals(UUID shopId) {
        if (shopId == null) {
            return jdbc.sql("SELECT * FROM app.terminal ORDER BY code")
                    .query(this::mapTerminal).list();
        }
        return jdbc.sql("SELECT * FROM app.terminal WHERE shop_id = ? ORDER BY code")
                .param(shopId).query(this::mapTerminal).list();
    }

    int disableTerminal(UUID id) {
        return jdbc.sql("UPDATE app.terminal SET disabled_at = now(), updated_at = now() "
                        + "WHERE id = ? AND disabled_at IS NULL")
                .param(id).update();
    }

    // ----------------------------------------------------------------- mapping

    private ShopView mapShop(ResultSet rs, int rowNum) throws SQLException {
        return new ShopView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("document_prefix"),
                rs.getString("city"),
                rs.getString("timezone"),
                rs.getString("currency"),
                rs.getBoolean("selling_enabled"),
                rs.getBoolean("active"));
    }

    private TerminalView mapTerminal(ResultSet rs, int rowNum) throws SQLException {
        return new TerminalView(
                rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("printer_type"),
                rs.getInt("paper_width_mm"),
                rs.getBoolean("cash_drawer_enabled"),
                rs.getTimestamp("disabled_at") != null);
    }
}
