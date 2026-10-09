package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.akshara.support.IntegrationTest;

class DatabaseRoleGuardIT extends IntegrationTest {

    @Autowired
    DatabaseRoleGuard guard;

    @Autowired
    AksharaProperties properties;

    @Test
    void theApiRunsAsARoleThatCannotBypassRowLevelSecurity() {
        assertThat(guard.check()).isNull();
    }

    @Test
    void startingAsTheOwnerOrASuperuserIsRefused() throws Exception {
        var owner = new SingleConnectionDataSource(ownerConnection(), true);
        try {
            DatabaseRoleGuard ownerGuard = new DatabaseRoleGuard(owner, properties);
            assertThat(ownerGuard.check()).contains("superuser");
            assertThatThrownBy(() -> ownerGuard.run(null)).isInstanceOf(IllegalStateException.class);
        } finally {
            owner.destroy();
        }
    }
}
