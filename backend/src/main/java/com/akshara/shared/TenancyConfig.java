package com.akshara.shared;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    /** Sentinel tenant for platform-level work (no school). Hibernate applies no tenant filter for it. */
    public static final UUID ROOT_TENANT = new UUID(0L, 0L);

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)
                        && "dataSource".equals(beanName)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    @Bean
    CurrentTenantIdentifierResolver<UUID> tenantIdentifierResolver() {
        return new CurrentTenantIdentifierResolver<>() {
            @Override
            public UUID resolveCurrentTenantIdentifier() {
                return TenantContext.current().orElse(ROOT_TENANT);
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return false;
            }

            @Override
            public boolean isRoot(UUID tenantId) {
                return ROOT_TENANT.equals(tenantId);
            }
        };
    }

    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer(CurrentTenantIdentifierResolver<UUID> resolver) {
        return (Map<String, Object> properties) ->
                properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
