package com.pigpurchases.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Replaces Spring Boot's default DataSource with a {@link SwitchableDataSource}
 * wrapping the live database, so a restore preview can temporarily route the whole
 * app at a backup without disturbing the live db. The live pool is still built
 * from the ordinary {@code spring.datasource.*} properties, so nothing else about
 * the datasource changes.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public SwitchableDataSource dataSource(DataSourceProperties properties) {
        DataSource live = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new SwitchableDataSource(live);
    }
}
