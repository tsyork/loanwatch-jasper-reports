package com.jasperdev.reports.model;

/**
 * Represents a parameter extracted from a JRXML report file.
 */
public class ReportParameter {
    private final String name;
    private final String type;
    private final String description;
    private final String defaultValue;
    private final boolean required;

    public ReportParameter(String name, String type, String description, String defaultValue, boolean required) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.defaultValue = defaultValue;
        this.required = required;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * Returns the appropriate HTML input type for this parameter.
     */
    public String getHtmlInputType() {
        if (type == null) return "text";

        return switch (type.toLowerCase()) {
            case "java.lang.integer", "java.lang.long", "java.lang.short",
                 "java.lang.float", "java.lang.double", "java.math.bigdecimal",
                 "integer", "long", "short", "float", "double", "number" -> "number";
            case "java.util.date", "java.sql.date", "date" -> "date";
            case "java.sql.timestamp", "timestamp", "datetime" -> "datetime-local";
            case "java.lang.boolean", "boolean" -> "checkbox";
            default -> "text";
        };
    }

    /**
     * Returns the simple type name for display purposes.
     */
    public String getSimpleTypeName() {
        if (type == null) return "String";
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    @Override
    public String toString() {
        return "ReportParameter{name='" + name + "', type='" + type + "', default='" + defaultValue + "'}";
    }
}
