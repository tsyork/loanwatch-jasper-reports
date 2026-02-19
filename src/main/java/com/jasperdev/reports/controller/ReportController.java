package com.jasperdev.reports.controller;

import com.jasperdev.reports.model.ReportInfo;
import com.jasperdev.reports.service.DataSourceService;
import com.jasperdev.reports.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for report listing, parameter input, and PDF generation.
 */
@Controller
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;
    private final DataSourceService dataSourceService;

    public ReportController(ReportService reportService, DataSourceService dataSourceService) {
        this.reportService = reportService;
        this.dataSourceService = dataSourceService;
    }

    /**
     * Home page - lists all available reports.
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("reports", reportService.getAvailableReports());
        model.addAttribute("dataSources", dataSourceService.getDataSourceNames());
        return "home";
    }

    /**
     * Report parameter form page.
     */
    @GetMapping("/report/{id}")
    public String reportForm(@PathVariable String id, Model model) {
        ReportInfo report = reportService.getReportById(id);

        if (report == null) {
            model.addAttribute("error", "Report not found: " + id);
            return "error";
        }

        model.addAttribute("report", report);
        model.addAttribute("dataSources", dataSourceService.getDataSourceNames());
        model.addAttribute("defaultDataSource", dataSourceService.getDefaultDataSourceName());
        return "report-form";
    }

    /**
     * Generate PDF endpoint - handles form submission.
     */
    @PostMapping("/report/{id}/generate")
    public ResponseEntity<?> generateReport(
            @PathVariable String id,
            @RequestParam String dataSource,
            HttpServletRequest request) {

        ReportInfo report = reportService.getReportById(id);

        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Report not found: " + id);
        }

        // Collect all parameters from the form
        Map<String, String> parameters = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (!key.equals("dataSource") && values.length > 0) {
                parameters.put(key, values[0]);
            }
        });

        try {
            byte[] pdf = reportService.generatePdf(report, dataSource, parameters);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            // Inline display in browser
            headers.setContentDispositionFormData("inline", report.getDisplayName() + ".pdf");
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + report.getDisplayName() + ".pdf\"");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdf);

        } catch (Exception e) {
            log.error("Error generating report {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildErrorHtml(report, e));
        }
    }

    /**
     * API endpoint to refresh reports (trigger hot-reload).
     */
    @PostMapping("/api/refresh")
    @ResponseBody
    public Map<String, Object> refreshReports() {
        reportService.scanReports();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("reportCount", reportService.getAvailableReports().size());
        return result;
    }

    /**
     * API endpoint to test data source connection.
     */
    @GetMapping("/api/datasource/{name}/test")
    @ResponseBody
    public Map<String, Object> testDataSource(@PathVariable String name) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", name);
        result.put("connected", dataSourceService.testConnection(name));
        return result;
    }

    /**
     * API endpoint to upload a new report.
     */
    @PostMapping("/api/reports/upload")
    @ResponseBody
    public Map<String, Object> uploadReport(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();

        if (!reportService.isUploadEnabled()) {
            result.put("success", false);
            result.put("error", "Upload not enabled. Set REPORTS_PATH environment variable.");
            return result;
        }

        if (file.isEmpty()) {
            result.put("success", false);
            result.put("error", "No file provided");
            return result;
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".jrxml")) {
            result.put("success", false);
            result.put("error", "File must be a .jrxml file");
            return result;
        }

        try {
            reportService.saveReport(fileName, file.getBytes());
            result.put("success", true);
            result.put("fileName", fileName);
            result.put("reportCount", reportService.getAvailableReports().size());
        } catch (Exception e) {
            log.error("Error uploading report: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * API endpoint to delete a report.
     */
    @DeleteMapping("/api/reports/{fileName}")
    @ResponseBody
    public Map<String, Object> deleteReport(@PathVariable String fileName) {
        Map<String, Object> result = new HashMap<>();

        try {
            reportService.deleteReport(fileName);
            result.put("success", true);
            result.put("reportCount", reportService.getAvailableReports().size());
        } catch (Exception e) {
            log.error("Error deleting report: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * API endpoint to check upload status.
     */
    @GetMapping("/api/reports/upload-status")
    @ResponseBody
    public Map<String, Object> uploadStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", reportService.isUploadEnabled());
        result.put("directory", reportService.getReportsDirectoryPath());
        result.put("imagesDirectory", reportService.getImagesDirectoryPath());
        return result;
    }

    /**
     * API endpoint to upload an image.
     */
    @PostMapping("/api/images/upload")
    @ResponseBody
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();

        if (!reportService.isUploadEnabled()) {
            result.put("success", false);
            result.put("error", "Upload not enabled. Set REPORTS_PATH environment variable.");
            return result;
        }

        if (file.isEmpty()) {
            result.put("success", false);
            result.put("error", "No file provided");
            return result;
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            result.put("success", false);
            result.put("error", "Invalid file name");
            return result;
        }

        try {
            reportService.saveImage(fileName, file.getBytes());
            result.put("success", true);
            result.put("fileName", fileName);
            result.put("images", reportService.listImages());
        } catch (Exception e) {
            log.error("Error uploading image: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * API endpoint to delete an image.
     */
    @DeleteMapping("/api/images/{fileName}")
    @ResponseBody
    public Map<String, Object> deleteImage(@PathVariable String fileName) {
        Map<String, Object> result = new HashMap<>();

        try {
            reportService.deleteImage(fileName);
            result.put("success", true);
            result.put("images", reportService.listImages());
        } catch (Exception e) {
            log.error("Error deleting image: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * API endpoint to list images.
     */
    @GetMapping("/api/images")
    @ResponseBody
    public Map<String, Object> listImages() {
        Map<String, Object> result = new HashMap<>();
        result.put("images", reportService.listImages());
        result.put("directory", reportService.getImagesDirectoryPath());
        return result;
    }

    /**
     * Builds an error HTML page for report generation failures.
     */
    private String buildErrorHtml(ReportInfo report, Exception e) {
        String message = sanitizeErrorMessage(e.getMessage());
        String cause = e.getCause() != null ? sanitizeErrorMessage(e.getCause().getMessage()) : "";

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Report Error</title>
                <style>
                    body { font-family: sans-serif; padding: 40px; background: #f5f5f5; }
                    .error-box { background: white; padding: 30px; border-radius: 8px;
                                 border-left: 4px solid #dc3545; max-width: 800px; margin: 0 auto; }
                    h1 { color: #dc3545; margin-top: 0; }
                    .message { background: #f8f9fa; padding: 15px; border-radius: 4px;
                               font-family: monospace; overflow-x: auto; }
                    .back-link { display: inline-block; margin-top: 20px; color: #0066cc; }
                    .hints { margin-top: 20px; padding: 15px; background: #fff3cd; border-radius: 4px; }
                    .hints h3 { margin-top: 0; color: #856404; }
                    .hints ul { margin-bottom: 0; }
                </style>
            </head>
            <body>
                <div class="error-box">
                    <h1>Report Generation Failed</h1>
                    <p><strong>Report:</strong> %s</p>
                    <div class="message">%s</div>
                    %s
                    <div class="hints">
                        <h3>Troubleshooting</h3>
                        <ul>
                            <li>Check that the selected data source is configured correctly</li>
                            <li>Verify database connectivity and credentials</li>
                            <li>Ensure all required parameters have valid values</li>
                            <li>Check the JRXML file for syntax errors</li>
                            <li>Review application logs for detailed error information</li>
                        </ul>
                    </div>
                    <a href="/report/%s" class="back-link">&larr; Back to Report Form</a>
                </div>
            </body>
            </html>
            """.formatted(
                escapeHtml(report.getDisplayName()),
                escapeHtml(message),
                cause.isEmpty() ? "" : "<p><strong>Cause:</strong> " + escapeHtml(cause) + "</p>",
                report.getId()
        );
    }

    /**
     * Sanitizes error messages to avoid exposing sensitive information.
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) return "Unknown error";

        // Remove potential sensitive info like passwords, connection strings
        return message
                .replaceAll("password=[^&\\s]+", "password=***")
                .replaceAll("PASSWORD=[^&\\s]+", "PASSWORD=***")
                .replaceAll("jdbc:[^\\s]+@", "jdbc:***@");
    }

    /**
     * Basic HTML escaping.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
