package com.jasperdev.reports.service;

import com.jasperdev.reports.model.ReportInfo;
import com.jasperdev.reports.model.ReportParameter;
import com.jasperdev.reports.util.ParameterExtractor;
import jakarta.annotation.PostConstruct;
import net.sf.jasperreports.engine.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Connection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing JasperReports: scanning, compiling, and generating PDFs.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final DataSourceService dataSourceService;

    @Value("${jasper.reports.path:classpath:reports/}")
    private String reportsPath;

    @Value("${jasper.reports.external.path:}")
    private String externalReportsPath;

    @Value("${jasper.temp.directory:#{systemProperties['java.io.tmpdir']}/jasper-reports}")
    private String tempDirectory;

    // Filesystem path for development hot-reload or production uploads
    private Path filesystemReportsPath;

    // Whether we're in production mode with external storage
    private boolean productionMode = false;

    // Cache of compiled reports: fileName -> (lastModified, JasperReport)
    private final Map<String, CompiledReport> compiledReports = new ConcurrentHashMap<>();

    // Cache of report metadata
    private final Map<String, ReportInfo> reportInfoCache = new ConcurrentHashMap<>();

    // Track file modification times for hot-reload
    private final Map<String, Long> fileModificationTimes = new ConcurrentHashMap<>();

    public ReportService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @PostConstruct
    public void initialize() {
        // Create temp directory if needed
        try {
            Files.createDirectories(Path.of(tempDirectory));
        } catch (IOException e) {
            log.warn("Could not create temp directory: {}", e.getMessage());
        }

        // Check for filesystem reports folder (development mode only)
        // Skip filesystem scanning if running on Railway or other production environments
        String railwayEnv = System.getenv("RAILWAY_ENVIRONMENT");
        String port = System.getenv("PORT");
        productionMode = railwayEnv != null || (port != null && !port.equals("8080"));

        if (!productionMode) {
            // Development mode: use src/main/resources/reports
            Path devPath = Path.of("src/main/resources/reports");
            if (Files.isDirectory(devPath)) {
                filesystemReportsPath = devPath;
                log.info("Development mode: scanning filesystem at {}", devPath.toAbsolutePath());
            }
        } else {
            // Production mode: use external directory if configured
            if (externalReportsPath != null && !externalReportsPath.isEmpty()) {
                Path extPath = Path.of(externalReportsPath);
                try {
                    Files.createDirectories(extPath);
                    filesystemReportsPath = extPath;
                    log.info("Production mode: using external reports directory at {}", extPath.toAbsolutePath());
                } catch (IOException e) {
                    log.error("Could not create external reports directory: {}", e.getMessage());
                }
            } else {
                log.info("Production mode: scanning classpath for reports (no external directory configured)");
            }
        }

        // Initial scan for reports
        scanReports();
    }

    /**
     * Scans the reports directory and loads metadata for all JRXML files.
     */
    public void scanReports() {
        Set<String> foundReports = new HashSet<>();

        // In production with external directory: scan both classpath and external
        if (productionMode && filesystemReportsPath != null) {
            scanClasspath(foundReports);
            scanFilesystem(foundReports);
        } else if (filesystemReportsPath != null) {
            // Development mode: scan filesystem only
            scanFilesystem(foundReports);
        } else {
            // Production without external directory: scan classpath only
            scanClasspath(foundReports);
        }

        // Remove reports that no longer exist
        reportInfoCache.keySet().removeIf(name -> !foundReports.contains(name));
        compiledReports.keySet().removeIf(name -> !foundReports.contains(name));
        fileModificationTimes.keySet().removeIf(name -> !foundReports.contains(name));

        log.debug("Found {} total reports", reportInfoCache.size());
    }

    /**
     * Scans filesystem directly (development mode or external directory).
     */
    private void scanFilesystem(Set<String> foundReports) {
        log.debug("Scanning filesystem for reports in: {}", filesystemReportsPath);

        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(filesystemReportsPath, "*.jrxml")) {
                for (Path path : stream) {
                    String fileName = path.getFileName().toString();
                    foundReports.add(fileName);

                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    Long cachedModTime = fileModificationTimes.get(fileName);

                    if (cachedModTime == null || cachedModTime != lastModified) {
                        loadReportInfoFromPath(path);
                        fileModificationTimes.put(fileName, lastModified);
                        compiledReports.remove(fileName);
                        log.info("Loaded/reloaded report from filesystem: {}", fileName);
                    }
                }
            }

            log.debug("Found {} reports in filesystem", foundReports.size());

        } catch (IOException e) {
            log.error("Error scanning filesystem reports directory: {}", e.getMessage());
        }
    }

    /**
     * Scans classpath (production mode).
     */
    private void scanClasspath(Set<String> foundReports) {
        log.debug("Scanning classpath for reports in: {}", reportsPath);

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            String pattern = reportsPath + "*.jrxml";
            Resource[] resources = resolver.getResources(pattern);

            for (Resource resource : resources) {
                try {
                    String fileName = resource.getFilename();
                    if (fileName == null) continue;

                    // Skip if already found in external directory (external takes precedence)
                    if (foundReports.contains(fileName)) continue;

                    foundReports.add(fileName);

                    // Check if we need to reload this report
                    long lastModified = resource.lastModified();
                    Long cachedModTime = fileModificationTimes.get(fileName);

                    if (cachedModTime == null || cachedModTime != lastModified) {
                        loadReportInfo(resource);
                        fileModificationTimes.put(fileName, lastModified);
                        // Invalidate compiled cache
                        compiledReports.remove(fileName);
                        log.info("Loaded/reloaded report from classpath: {}", fileName);
                    }
                } catch (Exception e) {
                    log.warn("Error processing resource {}: {}", resource, e.getMessage());
                }
            }

            log.debug("Found {} reports in classpath", foundReports.size());

        } catch (IOException e) {
            log.error("Error scanning reports directory: {}", e.getMessage());
        }
    }

    /**
     * Scheduled task to check for new/modified reports (hot-reload).
     */
    @Scheduled(fixedDelay = 5000)
    public void checkForChanges() {
        scanReports();
    }

    /**
     * Loads report metadata from a filesystem path (development mode).
     */
    private void loadReportInfoFromPath(Path path) throws IOException {
        String fileName = path.getFileName().toString();

        String displayName = ParameterExtractor.extractReportName(path);
        List<ReportParameter> parameters = ParameterExtractor.extractParameters(path);

        LocalDateTime lastModified = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(path).toInstant(),
                ZoneId.systemDefault()
        );

        ReportInfo info = new ReportInfo(fileName, displayName, path.toString(), parameters, lastModified);
        reportInfoCache.put(fileName, info);
    }

    /**
     * Loads report metadata from a classpath resource.
     */
    private void loadReportInfo(Resource resource) throws IOException {
        String fileName = resource.getFilename();
        Path tempPath = Files.createTempFile("jrxml_", ".jrxml");

        try {
            // Copy to temp file for parsing
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }

            String displayName = ParameterExtractor.extractReportName(tempPath);
            List<ReportParameter> parameters = ParameterExtractor.extractParameters(tempPath);

            LocalDateTime lastModified = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(resource.lastModified()),
                    ZoneId.systemDefault()
            );

            ReportInfo info = new ReportInfo(fileName, displayName, reportsPath + fileName, parameters, lastModified);
            reportInfoCache.put(fileName, info);

        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    /**
     * Returns all available reports.
     */
    public List<ReportInfo> getAvailableReports() {
        List<ReportInfo> reports = new ArrayList<>(reportInfoCache.values());
        reports.sort(Comparator.comparing(ReportInfo::getDisplayName));
        return reports;
    }

    /**
     * Gets report info by filename.
     */
    public ReportInfo getReportByFileName(String fileName) {
        return reportInfoCache.get(fileName);
    }

    /**
     * Finds a report by its URL-safe ID.
     */
    public ReportInfo getReportById(String id) {
        return reportInfoCache.values().stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Generates a PDF report.
     *
     * @param reportInfo      The report to generate
     * @param dataSourceName  The data source to use
     * @param parameters      User-provided parameters
     * @return PDF as byte array
     */
    public byte[] generatePdf(ReportInfo reportInfo, String dataSourceName, Map<String, String> parameters)
            throws Exception {

        // Compile if needed
        JasperReport jasperReport = getCompiledReport(reportInfo);

        // Convert string parameters to proper types
        Map<String, Object> typedParams = convertParameters(reportInfo, parameters);

        // Get database connection
        try (Connection connection = dataSourceService.getConnection(dataSourceName)) {
            // Fill the report
            JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, typedParams, connection);

            // Export to PDF
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JasperExportManager.exportReportToPdfStream(jasperPrint, baos);

            log.info("Generated PDF for report: {} using datasource: {}", reportInfo.getDisplayName(), dataSourceName);
            return baos.toByteArray();
        }
    }

    /**
     * Gets a compiled JasperReport, compiling from JRXML if necessary.
     */
    private JasperReport getCompiledReport(ReportInfo reportInfo) throws Exception {
        String fileName = reportInfo.getFileName();

        CompiledReport cached = compiledReports.get(fileName);
        Long currentModTime = fileModificationTimes.get(fileName);

        if (cached != null && currentModTime != null && cached.lastModified == currentModTime) {
            return cached.report;
        }

        // Need to compile
        log.info("Compiling report: {}", fileName);

        // Try filesystem first (development mode or external directory)
        if (filesystemReportsPath != null) {
            Path jrxmlPath = filesystemReportsPath.resolve(fileName);
            if (Files.exists(jrxmlPath)) {
                try (InputStream is = Files.newInputStream(jrxmlPath)) {
                    JasperReport report = JasperCompileManager.compileReport(is);
                    compiledReports.put(fileName, new CompiledReport(currentModTime != null ? currentModTime : 0, report));
                    return report;
                }
            }
        }

        // Fall back to classpath (use classpath: not classpath*: for single resource)
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String resourcePath = "classpath:reports/" + fileName;
        Resource resource = resolver.getResource(resourcePath);

        try (InputStream is = resource.getInputStream()) {
            JasperReport report = JasperCompileManager.compileReport(is);
            compiledReports.put(fileName, new CompiledReport(currentModTime != null ? currentModTime : 0, report));
            return report;
        }
    }

    /**
     * Converts string parameters to their proper Java types.
     */
    private Map<String, Object> convertParameters(ReportInfo reportInfo, Map<String, String> stringParams) {
        Map<String, Object> result = new HashMap<>();

        for (ReportParameter param : reportInfo.getParameters()) {
            String stringValue = stringParams.get(param.getName());

            // Use default if no value provided
            if (stringValue == null || stringValue.isEmpty()) {
                stringValue = param.getDefaultValue();
            }

            if (stringValue == null || stringValue.isEmpty()) {
                continue;
            }

            try {
                Object value = convertToType(stringValue, param.getType());
                if (value != null) {
                    result.put(param.getName(), value);
                }
            } catch (Exception e) {
                log.warn("Could not convert parameter {} value '{}' to {}: {}",
                        param.getName(), stringValue, param.getType(), e.getMessage());
            }
        }

        return result;
    }

    /**
     * Converts a string value to the specified Java type.
     */
    private Object convertToType(String value, String type) throws ParseException {
        if (value == null || value.isEmpty()) {
            return null;
        }

        return switch (type.toLowerCase()) {
            case "java.lang.string" -> value;
            case "java.lang.integer" -> Integer.parseInt(value);
            case "java.lang.long" -> Long.parseLong(value);
            case "java.lang.short" -> Short.parseShort(value);
            case "java.lang.float" -> Float.parseFloat(value);
            case "java.lang.double" -> Double.parseDouble(value);
            case "java.math.bigdecimal" -> new BigDecimal(value);
            case "java.lang.boolean" -> Boolean.parseBoolean(value);
            case "java.util.date" -> parseDate(value);
            case "java.sql.date" -> new java.sql.Date(parseDate(value).getTime());
            case "java.sql.timestamp" -> java.sql.Timestamp.valueOf(value.replace("T", " "));
            default -> value;
        };
    }

    /**
     * Parses a date string in various formats.
     */
    private Date parseDate(String value) throws ParseException {
        // Try common date formats
        String[] formats = {"yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd'T'HH:mm"};

        for (String format : formats) {
            try {
                return new SimpleDateFormat(format).parse(value);
            } catch (ParseException ignored) {
            }
        }

        throw new ParseException("Could not parse date: " + value, 0);
    }

    /**
     * Saves an uploaded JRXML file.
     *
     * @param fileName The name of the file
     * @param content  The file content
     * @return true if saved successfully
     */
    public boolean saveReport(String fileName, byte[] content) throws IOException {
        if (filesystemReportsPath == null) {
            throw new IOException("No writable reports directory configured. Set REPORTS_PATH environment variable.");
        }

        // Validate filename
        if (!fileName.endsWith(".jrxml")) {
            throw new IOException("File must have .jrxml extension");
        }

        // Sanitize filename to prevent path traversal
        String sanitizedName = Path.of(fileName).getFileName().toString();
        Path targetPath = filesystemReportsPath.resolve(sanitizedName);

        Files.write(targetPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Saved uploaded report: {}", sanitizedName);

        // Trigger rescan to pick up the new file
        scanReports();

        return true;
    }

    /**
     * Deletes a report file.
     *
     * @param fileName The name of the file to delete
     * @return true if deleted successfully
     */
    public boolean deleteReport(String fileName) throws IOException {
        if (filesystemReportsPath == null) {
            throw new IOException("No writable reports directory configured");
        }

        // Sanitize filename to prevent path traversal
        String sanitizedName = Path.of(fileName).getFileName().toString();
        Path targetPath = filesystemReportsPath.resolve(sanitizedName);

        if (!Files.exists(targetPath)) {
            throw new IOException("Report not found in external directory: " + sanitizedName);
        }

        Files.delete(targetPath);
        log.info("Deleted report: {}", sanitizedName);

        // Clear caches
        reportInfoCache.remove(sanitizedName);
        compiledReports.remove(sanitizedName);
        fileModificationTimes.remove(sanitizedName);

        return true;
    }

    /**
     * Checks if uploads are enabled.
     */
    public boolean isUploadEnabled() {
        return filesystemReportsPath != null;
    }

    /**
     * Gets the path to the reports directory (for display purposes).
     */
    public String getReportsDirectoryPath() {
        return filesystemReportsPath != null ? filesystemReportsPath.toString() : "classpath";
    }

    /**
     * Holder for compiled report with modification timestamp.
     */
    private static class CompiledReport {
        final long lastModified;
        final JasperReport report;

        CompiledReport(long lastModified, JasperReport report) {
            this.lastModified = lastModified;
            this.report = report;
        }
    }
}
