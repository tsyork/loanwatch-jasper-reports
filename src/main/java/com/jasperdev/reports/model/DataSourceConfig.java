package com.jasperdev.reports.model;

/**
 * Configuration holder for a single data source.
 */
public class DataSourceConfig {
    private final String name;
    private final String url;
    private final String username;
    private final String password;
    private final String driver;

    public DataSourceConfig(String name, String url, String username, String password, String driver) {
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.driver = driver;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDriver() {
        return driver;
    }

    @Override
    public String toString() {
        return "DataSourceConfig{name='" + name + "', url='" + url + "'}";
    }
}
