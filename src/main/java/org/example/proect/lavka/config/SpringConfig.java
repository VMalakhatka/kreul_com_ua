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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ViewResolverRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


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


    @Bean(name = "folioDataSource")
    @Primary
    public DataSource dataSource(DatabaseProperties databaseProperties) {
        HikariConfig cfg = new HikariConfig();

        cfg.setDriverClassName(databaseProperties.getDriverClassName());
        cfg.setJdbcUrl(databaseProperties.getUrl());
        cfg.setUsername(databaseProperties.getUsername());
        cfg.setPassword(databaseProperties.getPassword());

        // 🧠 Параметры пула и таймаутов (важно при обрывах)
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(20_000);     // ждать до 20 сек получения коннекта
        cfg.setIdleTimeout(60_000);           // не закрывать раньше минуты
        cfg.setKeepaliveTime(30_000);         // поддерживать соединение каждые 30 сек
        cfg.setValidationTimeout(5_000);
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setInitializationFailTimeout(-1); // не падать при старте, если база недоступна

        return new HikariDataSource(cfg);
    }

    @Bean(name = "folioJdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("folioDataSource")DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "folioNamedJdbc")
    public NamedParameterJdbcTemplate namedJdbcTemplate(@Qualifier("folioDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
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
