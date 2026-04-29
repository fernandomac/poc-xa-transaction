package com.example.xapoc.config;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.mysql.cj.jdbc.MysqlXADataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Configures the MySQL XA datasource wrapped in an Atomikos connection pool.
 * The Atomikos Spring Boot 3 starter auto-configures the JTA transaction manager;
 * we only need to declare the XA DataSource bean here.
 */
@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.jta.atomikos.datasource.unique-resource-name:mysqlXaDs}")
    private String uniqueResourceName;

    @Value("${spring.jta.atomikos.datasource.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${spring.jta.atomikos.datasource.min-pool-size:2}")
    private int minPoolSize;

    @Bean
    public DataSource dataSource() {
        MysqlXADataSource mysqlXADataSource = new MysqlXADataSource();
        // URL must include allowXAStatements=true and pinGlobalTxToPhysicalConnection=true
        mysqlXADataSource.setUrl(url);
        mysqlXADataSource.setUser(username);
        mysqlXADataSource.setPassword(password);

        AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
        ds.setUniqueResourceName(uniqueResourceName);
        ds.setXaDataSource(mysqlXADataSource);
        ds.setMaxPoolSize(maxPoolSize);
        ds.setMinPoolSize(minPoolSize);
        ds.setBorrowConnectionTimeout(30);
        ds.setTestQuery("SELECT 1");
        return ds;
    }
}
