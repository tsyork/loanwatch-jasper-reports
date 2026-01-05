package com.jasperdev.reports.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Holds metadata about a report, including its parameters.
 */
public class ReportInfo {
    private final String fileName;
    private final String displayName;
    private final String filePath;
    private final List<ReportParameter> parameters;
    private final LocalDateTime lastModified;

    public ReportInfo(String fileName, String displayName, String filePath,
                      List<ReportParameter> parameters, LocalDateTime lastModified) {
        this.fileName = fileName;
        this.displayName = displayName;
        this.filePath = filePath;
        this.parameters = parameters;
        this.lastModified = lastModified;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFilePath() {
        return filePath;
    }

    public List<ReportParameter> getParameters() {
        return parameters;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    /**
     * Returns the URL-safe identifier for this report.
     */
    public String getId() {
        // Remove .jrxml extension and replace spaces with dashes
        String id = fileName;
        if (id.toLowerCase().endsWith(".jrxml")) {
            id = id.substring(0, id.length() - 6);
        }
        return id.replace(" ", "-");
    }

    @Override
    public String toString() {
        return "ReportInfo{fileName='" + fileName + "', params=" + parameters.size() + "}";
    }
}
