package org.example.proect.lavka.config;

import com.github.javafaker.Faker;
import org.example.proect.lavka.property.DatabaseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ViewResolverRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import javax.sql.DataSource;
import java.util.Random;

@Configuration
@ComponentScan("org.example.proect.lavka")
@EnableWebMvc
public class SpringConfig implements WebMvcConfigurer {
    private final ApplicationContext applicationContext;
    private final DatabaseProperties databaseProperties;

    @Autowired
    public SpringConfig(ApplicationContext applicationContext, DatabaseProperties databaseProperties) {
        this.applicationContext = applicationContext;
        this.databaseProperties = databaseProperties;
    }


    @Bean
    public SpringResourceTemplateResolver templateResolver() {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(applicationContext);
//        resolver.setPrefix("/WEB-INF/views/");
        resolver.setSuffix(".html");
        return resolver;
    }

    @Bean
    public SpringTemplateEngine templateEngine() {
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver());
        templateEngine.setEnableSpringELCompiler(true);
        return templateEngine;
    }

    @Override
    public void configureViewResolvers(ViewResolverRegistry registry) {
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(templateEngine());
        registry.viewResolver(viewResolver);

    }

//    @Bean
//    @DependsOn("databaseProperties")
//    DataSource dataSource(DatabaseProperties databaseProperties) {
//        DriverManagerDataSource dataSource = new DriverManagerDataSource();
//        dataSource.setDriverClassName(databaseProperties.driverClassName());
//        dataSource.setUrl(databaseProperties.url());
//        dataSource.setUsername(databaseProperties.username());
//        dataSource.setPassword(databaseProperties.password());
//        return dataSource;
//    }

    @Bean(name = "folioDataSource")
    @Primary
    DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(databaseProperties.getDriverClassName());
        dataSource.setUrl(databaseProperties.getUrl());
        dataSource.setUsername(databaseProperties.getUsername());
        dataSource.setPassword(databaseProperties.getPassword());
        return dataSource;
    }

    @Bean(name = "folioJdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("folioDataSource")DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public Random random() {
        return new Random();
    }

    @Bean
    public Faker faker() {
        return new Faker();
    }
}
