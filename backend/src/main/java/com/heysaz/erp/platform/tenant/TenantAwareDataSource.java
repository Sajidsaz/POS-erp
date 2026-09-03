package com.heysaz.erp.platform.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds {@code app.org_id} for the life of each transaction, so that the row-level
 * security policies written in V2 have something to compare against.
 *
 * <p>Appendix D decision D1 names this as the mechanic that has to be right: the
 * policies are only as good as the guarantee that the GUC is (a) always set from the
 * credential and (b) never survives into the next borrower of a pooled connection.
 *
 * <p>Both properties come from one choice — {@code set_config(..., is_local => true)},
 * the function form of {@code SET LOCAL}. Postgres resets a LOCAL setting at commit or
 * rollback, so a connection returning to the pool cannot carry a stale tenant with it.
 * {@code ConnectionGucLeakTest} asserts exactly that.
 *
 * <p>The bind is issued when Spring calls {@code setAutoCommit(false)} to open a
 * transaction. A consequence worth knowing: a query run <em>outside</em> a transaction
 * gets no LOCAL binding, so {@code app.current_org()} is NULL and it reads zero rows.
 * That is deliberate. Tenant data access belongs in a transaction, and the failure mode
 * for forgetting is an empty result, never another tenant's rows.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String BIND_SQL = "SELECT set_config('app.org_id', ?, true)";

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    if ("setAutoCommit".equals(method.getName())
                            && args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                        bindTenant(target);
                    }
                    return result;
                });
    }

    private static void bindTenant(Connection connection) throws SQLException {
        UUID orgId = TenantContext.orgIdOrNull();
        // Bound as a parameter, never interpolated: the value reaches Postgres as data.
        try (PreparedStatement ps = connection.prepareStatement(BIND_SQL)) {
            ps.setString(1, orgId == null ? "" : orgId.toString());
            ps.execute();
        }
    }
}
