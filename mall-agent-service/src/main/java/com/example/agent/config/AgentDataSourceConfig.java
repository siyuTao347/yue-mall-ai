package com.example.agent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 双数据源配置：
 * <ul>
 *   <li>主数据源 MySQL（db_agent）：MyBatis-Plus 与本地事务，RAG 元数据权威源</li>
 *   <li>向量数据源 PostgreSQL + pgvector：只走 JdbcTemplate，不参与 MySQL 事务</li>
 * </ul>
 * 显式声明第二个 DataSource 后 Spring Boot 的数据源自动配置会退让，
 * 因此主数据源必须显式声明并标注 {@link Primary}。
 */
@Configuration
public class AgentDataSourceConfig {

    @Bean("primaryDataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean("vectorDataSource")
    @ConfigurationProperties("rag.pgvector.datasource")
    public DataSource vectorDataSource() {
        return new HikariDataSource();
    }

    @Bean("vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(3);
        template.setMaxRows(1000);
        return template;
    }

    @Bean("vectorTransactionTemplate")
    public TransactionTemplate vectorTransactionTemplate(@Qualifier("vectorDataSource") DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
