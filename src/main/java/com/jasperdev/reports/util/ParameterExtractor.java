package com.jasperdev.reports.util;

import com.jasperdev.reports.model.ReportParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Utility for extracting parameter definitions from JRXML files.
 */
public class ParameterExtractor {

    private static final Logger log = LoggerFactory.getLogger(ParameterExtractor.class);

    // Built-in JasperReports parameters that should not be shown to users
    private static final Set<String> BUILT_IN_PARAMETERS = Set.of(
            "REPORT_CONNECTION",
            "REPORT_DATA_SOURCE",
            "REPORT_PARAMETERS_MAP",
            "REPORT_LOCALE",
            "REPORT_RESOURCE_BUNDLE",
            "REPORT_TIME_ZONE",
            "REPORT_VIRTUALIZER",
            "REPORT_CLASS_LOADER",
            "REPORT_URL_HANDLER_FACTORY",
            "REPORT_FILE_RESOLVER",
            "REPORT_SCRIPTLET",
            "REPORT_MAX_COUNT",
            "IS_IGNORE_PAGINATION",
            "REPORT_FORMAT_FACTORY",
            "REPORT_TEMPLATES",
            "REPORT_CONTEXT",
            "JASPER_REPORTS_CONTEXT",
            "FILTER"
    );

    /**
     * Extracts parameters from a JRXML file.
     */
    public static List<ReportParameter> extractParameters(Path jrxmlPath) {
        List<ReportParameter> parameters = new ArrayList<>();

        try (InputStream is = Files.newInputStream(jrxmlPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // Disable external entity processing for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            NodeList paramNodes = doc.getElementsByTagName("parameter");

            for (int i = 0; i < paramNodes.getLength(); i++) {
                Element paramElement = (Element) paramNodes.item(i);
                ReportParameter param = parseParameter(paramElement);
                if (param != null && !BUILT_IN_PARAMETERS.contains(param.getName())) {
                    parameters.add(param);
                }
            }

            log.debug("Extracted {} parameters from {}", parameters.size(), jrxmlPath.getFileName());

        } catch (Exception e) {
            log.error("Failed to extract parameters from {}: {}", jrxmlPath, e.getMessage());
        }

        return parameters;
    }

    /**
     * Extracts the report name from a JRXML file.
     */
    public static String extractReportName(Path jrxmlPath) {
        try (InputStream is = Files.newInputStream(jrxmlPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            Element root = doc.getDocumentElement();
            String name = root.getAttribute("name");

            return name != null && !name.isEmpty() ? name : jrxmlPath.getFileName().toString().replace(".jrxml", "");

        } catch (Exception e) {
            log.warn("Could not extract report name from {}: {}", jrxmlPath, e.getMessage());
            return jrxmlPath.getFileName().toString().replace(".jrxml", "");
        }
    }

    /**
     * Parses a single parameter element from the JRXML.
     */
    private static ReportParameter parseParameter(Element paramElement) {
        String name = paramElement.getAttribute("name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        String type = paramElement.getAttribute("class");
        if (type == null || type.isEmpty()) {
            type = "java.lang.String";
        }

        String description = null;
        String defaultValue = null;
        boolean required = false;

        // Check for isForPrompting attribute
        String isForPrompting = paramElement.getAttribute("isForPrompting");
        if ("false".equalsIgnoreCase(isForPrompting)) {
            // Skip parameters not meant for user input
            return null;
        }

        // Look for parameterDescription element
        NodeList descNodes = paramElement.getElementsByTagName("parameterDescription");
        if (descNodes.getLength() > 0) {
            Node descNode = descNodes.item(0);
            description = getTextContent(descNode);
        }

        // Look for defaultValueExpression element
        NodeList defaultNodes = paramElement.getElementsByTagName("defaultValueExpression");
        if (defaultNodes.getLength() > 0) {
            Node defaultNode = defaultNodes.item(0);
            String expr = getTextContent(defaultNode);
            // Try to extract a simple literal value from the expression
            defaultValue = extractLiteralValue(expr, type);
        } else {
            // If no default, it might be required
            required = true;
        }

        return new ReportParameter(name, type, description, defaultValue, required);
    }

    /**
     * Gets the text content from a node, handling CDATA sections.
     */
    private static String getTextContent(Node node) {
        StringBuilder sb = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE ||
                child.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(child.getTextContent());
            }
        }
        return sb.toString().trim();
    }

    /**
     * Attempts to extract a literal value from a JasperReports expression.
     */
    private static String extractLiteralValue(String expression, String type) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }

        String trimmed = expression.trim();

        // Handle numeric literals
        if (type.contains("Integer") || type.contains("Long") || type.contains("Short")) {
            // Match simple integers
            if (trimmed.matches("-?\\d+")) {
                return trimmed;
            }
        }

        // Handle boolean literals
        if (type.contains("Boolean")) {
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return trimmed.toLowerCase();
            }
        }

        // Handle string literals in quotes
        if (type.contains("String")) {
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }

        // Handle double/float literals
        if (type.contains("Double") || type.contains("Float") || type.contains("BigDecimal")) {
            if (trimmed.matches("-?\\d+\\.?\\d*[dDfF]?")) {
                return trimmed.replaceAll("[dDfF]$", "");
            }
        }

        // Return the raw expression as fallback
        return trimmed;
    }
}
