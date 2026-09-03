package com.heysaz.erp.platform.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Two connection pools, on purpose.
 *
 * <ul>
 *   <li><b>Primary</b> — connects as {@code erp_app}, which is subject to row-level
 *       security, and is wrapped so every transaction binds {@code app.org_id}. Every
 *       request-serving code path uses this and only this (DB-011).</li>
 *   <li><b>Elevated</b> — connects as the owner and therefore bypasses RLS. Reserved for
 *       the two things that genuinely have no tenant: authentication, which runs before a
 *       tenant is known, and the outbox worker, which spans all of them. Appendix D
 *       decision D1 calls this out as an explicit escape hatch; an ArchUnit rule keeps
 *       it from spreading.</li>
 * </ul>
 *
 * <p>Flyway gets a third, configured separately under {@code spring.flyway}, because
 * migrations must run as the owner and the application must not.
 */
@Configuration
public class DataAccessConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("erp.datasource.elevated")
    public DataSourceProperties elevatedDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties appDataSourceProperties) {
        HikariDataSource delegate = appDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        delegate.setPoolName("erp-app");
        return new TenantAwareDataSource(delegate);
    }

    @Bean
    public DataSource elevatedDataSource(
            @Qualifier("elevatedDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource dataSource = properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("erp-elevated");
        // Small on purpose: nothing on a request path should be waiting for one of these.
        dataSource.setMaximumPoolSize(4);
        return dataSource;
    }

    @Bean
    @Primary
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    public JdbcClient elevatedJdbcClient(@Qualifier("elevatedDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Declared explicitly, and marked primary, because Spring Boot's auto-configured
     * {@code TransactionTemplate} backs off as soon as any TransactionTemplate bean exists
     * — and {@code elevatedTransactionTemplate} below is one. Without this, an unqualified
     * injection silently resolves to the elevated, RLS-bypassing template. That is not a
     * hypothetical: it is the bug {@code ConnectionGucLeakTest} caught, and it had already
     * put permission resolution on the wrong pool.
     */
    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public TransactionTemplate elevatedTransactionTemplate(
            @Qualifier("elevatedDataSource") DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** DB-004 / decision D4: money crosses the wire as a string, never a JSON number. */
    @Bean
    public SimpleModule moneyModule() {
        return Money.jacksonModule();
    }
}
