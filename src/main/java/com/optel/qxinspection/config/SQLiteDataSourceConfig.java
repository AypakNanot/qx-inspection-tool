package com.optel.qxinspection.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite数据源配置（本地缓存库）
 * 
 * @author Rwj
 * @since 2026-08-20
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.optel.qxinspection.repository.sqlite",
        entityManagerFactoryRef = "sqliteEntityManagerFactory",
        transactionManagerRef = "sqliteTransactionManager"
)
public class SQLiteDataSourceConfig {

    @Value("${spring.jpa.show-sql:false}")
    private boolean showSql;

    @Value("${spring.jpa.properties.hibernate.format_sql:false}")
    private boolean formatSql;

    /**
     * SQLite数据源属性配置
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.sqlite")
    public DataSourceProperties sqliteDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * SQLite数据源
     */
    @Bean
    public DataSource sqliteDataSource() {
        DataSourceProperties properties = sqliteDataSourceProperties();
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        // SQLite 只支持单写，连接池设为1避免并发锁冲突
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(1);

        return dataSource;
    }

    /**
     * SQLite实体管理器工厂
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean sqliteEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("sqliteDataSource") DataSource dataSource) {
        
        Map<String, Object> properties = new HashMap<>();
        // 使用SQLite方言
        properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
        properties.put("hibernate.hbm2ddl.auto", "update"); // 自动创建/更新表结构
        properties.put("hibernate.show_sql", showSql);
        properties.put("hibernate.format_sql", formatSql);
        
        return builder
                .dataSource(dataSource)
                .packages("com.optel.qxinspection.entity.sqlite")
                .persistenceUnit("sqlite")
                .properties(properties)
                .build();
    }

    /**
     * SQLite事务管理器
     */
    @Bean
    public PlatformTransactionManager sqliteTransactionManager(
            @Qualifier("sqliteEntityManagerFactory") LocalContainerEntityManagerFactoryBean sqliteEntityManagerFactory) {
        return new JpaTransactionManager(sqliteEntityManagerFactory.getObject());
    }
}
