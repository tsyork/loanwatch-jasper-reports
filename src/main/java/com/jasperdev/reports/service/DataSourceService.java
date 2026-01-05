package com.jasperdev.reports.service;

import com.jasperdev.reports.model.DataSourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for managing multiple database connections for JasperReports.
 * Loads data source configurations from datasources.properties and provides
 * JDBC connections on demand.
 */
@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);
    private static final String DATASOURCES_FILE = "datasources.properties";
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");

    private final Environment environment;
    private final Map<String, DataSourceConfig> configurations = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    public DataSourceService(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void initialize() {
        loadDataSourceConfigurations();
    }

    @PreDestroy
    public void cleanup() {
        dataSources.values().forEach(ds -> {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("Error closing data source: {}", e.getMessage());
            }
        });
        dataSources.clear();
    }

    /**
     * Loads all data source configurations from datasources.properties.
     */
    private void loadDataSourceConfigurations() {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource(DATASOURCES_FILE).getInputStream()) {
            props.load(is);
        } catch (IOException e) {
            log.error("Failed to load datasources.properties: {}", e.getMessage());
            return;
        }

        // Find all unique data source names
        Set<String> names = new HashSet<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("datasource.")) {
                String[] parts = key.split("\\.");
                if (parts.length >= 3) {
                    names.add(parts[1]);
                }
            }
        }

        // Build configuration for each data source
        for (String name : names) {
            String prefix = "datasource." + name + ".";
            String url = resolveValue(props.getProperty(prefix + "url"));
            String username = resolveValue(props.getProperty(prefix + "username"));
            String password = resolveValue(props.getProperty(prefix + "password"));
            String driver = resolveValue(props.getProperty(prefix + "driver", "org.postgresql.Driver"));

            if (url != null && username != null) {
                DataSourceConfig config = new DataSourceConfig(name, url, username, password, driver);
                configurations.put(name, config);
                log.info("Loaded data source configuration: {} -> {}", name, url);
            } else {
                log.warn("Incomplete configuration for data source: {}", name);
            }
        }

        log.info("Loaded {} data source configurations", configurations.size());
    }

    /**
     * Resolves environment variable placeholders in a value.
     * Supports syntax: ${ENV_VAR:default_value}
     */
    private String resolveValue(String value) {
        if (value == null) return null;

        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String envVar = matcher.group(1);
            String defaultValue = matcher.group(2);

            // First check Spring environment, then system env
            String resolved = environment.getProperty(envVar);
            if (resolved == null) {
                resolved = System.getenv(envVar);
            }
            if (resolved == null) {
                resolved = defaultValue != null ? defaultValue : "";
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Returns a list of all configured data source names.
     */
    public List<String> getDataSourceNames() {
        List<String> names = new ArrayList<>(configurations.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * Returns the configuration for a specific data source.
     */
    public DataSourceConfig getConfiguration(String name) {
        return configurations.get(name);
    }

    /**
     * Gets a JDBC connection for the specified data source.
     */
    public Connection getConnection(String dataSourceName) throws SQLException {
        HikariDataSource ds = dataSources.computeIfAbsent(dataSourceName, this::createDataSource);
        if (ds == null) {
            throw new SQLException("Unknown data source: " + dataSourceName);
        }
        return ds.getConnection();
    }

    /**
     * Creates a HikariCP data source for the given configuration.
     */
    private HikariDataSource createDataSource(String name) {
        DataSourceConfig config = configurations.get(name);
        if (config == null) {
            log.error("No configuration found for data source: {}", name);
            return null;
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("jasper-" + name);
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName(config.getDriver());

        // Connection pool settings optimized for report generation
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setIdleTimeout(300000); // 5 minutes
        hikariConfig.setConnectionTimeout(30000); // 30 seconds
        hikariConfig.setMaxLifetime(600000); // 10 minutes

        try {
            HikariDataSource ds = new HikariDataSource(hikariConfig);
            log.info("Created connection pool for data source: {}", name);
            return ds;
        } catch (Exception e) {
            log.error("Failed to create data source {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Tests connectivity to a data source.
     */
    public boolean testConnection(String dataSourceName) {
        try (Connection conn = getConnection(dataSourceName)) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.warn("Connection test failed for {}: {}", dataSourceName, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the default data source name (first one alphabetically, or "demo" if available).
     */
    public String getDefaultDataSourceName() {
        if (configurations.containsKey("demo")) {
            return "demo";
        }
        List<String> names = getDataSourceNames();
        return names.isEmpty() ? null : names.get(0);
    }
}
