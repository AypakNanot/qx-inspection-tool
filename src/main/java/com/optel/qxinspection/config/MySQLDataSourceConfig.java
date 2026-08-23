package com.optel.qxinspection.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * MySQL数据源配置（老库只读）
 * 
 * @author Rwj
 * @since 2026-08-20
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.optel.qxinspection.repository.mysql",
        entityManagerFactoryRef = "mysqlEntityManagerFactory",
        transactionManagerRef = "mysqlTransactionManager"
)
public class MySQLDataSourceConfig {

    /**
     * MySQL数据源属性配置
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.mysql")
    public DataSourceProperties mysqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * MySQL数据源
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource() {
        DataSourceProperties properties = mysqlDataSourceProperties();
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        
        // 设置连接池参数
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(5);
        dataSource.setConnectionTimeout(30000);
        dataSource.setIdleTimeout(600000);
        dataSource.setMaxLifetime(1800000);
        
        return dataSource;
    }

    /**
     * MySQL实体管理器工厂
     */
    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean mysqlEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("mysqlDataSource") DataSource dataSource) {
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        properties.put("hibernate.hbm2ddl.auto", "none"); // 老库只读，不自动建表
        properties.put("hibernate.show_sql", true);
        properties.put("hibernate.format_sql", true);
        
        return builder
                .dataSource(dataSource)
                .packages("com.optel.qxinspection.entity.mysql")
                .persistenceUnit("mysql")
                .properties(properties)
                .build();
    }

    /**
     * MySQL事务管理器
     */
    @Bean
    @Primary
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("mysqlEntityManagerFactory") LocalContainerEntityManagerFactoryBean mysqlEntityManagerFactory) {
        return new JpaTransactionManager(mysqlEntityManagerFactory.getObject());
    }
}
