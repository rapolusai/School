package com.akshara.shared;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Stamps every pooled connection with {@code app.tenant_id} when it is borrowed and clears it when it is returned.
 * PostgreSQL row-level security policies read that setting, so a query can only ever see the current school's rows.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
        try {
            apply(connection, TenantContext.current().orElse(null));
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                        try {
                            if (!connection.isClosed()) {
                                apply(connection, null);
                            }
                        } finally {
                            connection.close();
                        }
                        return null;
                    }
                    if ("unwrap".equals(method.getName()) && args != null && args[0] == Connection.class) {
                        return proxy;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void apply(Connection connection, UUID tenantId) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        if (!autoCommit) {
            // Never let this housekeeping commit someone else's unfinished work.
            connection.rollback();
        }
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.execute();
        }
        if (!autoCommit) {
            // set_config(..., false) lasts for the session, but only once its own transaction commits.
            connection.commit();
        }
    }
}
