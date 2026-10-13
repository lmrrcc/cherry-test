package com.cherrytest.common.reportshell;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class ReportShell {
    public static final String REPORT_HTML_STYLES = resource("report-styles.css");
    public static final String REPORT_HTML_THEME_SCRIPT = resource("report-theme.js");
    public static final String REPORT_HTML_TEST_FILTER_SCRIPT = resource("report-test-filter.js");
    public static final String REPORT_HTML_JUMP_SCRIPT = resource("report-jump.js");

    private ReportShell() {}

    public static String renderCombinedReportDocument(String title, String statusHtml, String mainBodyHtml) {
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>"
                + htmlEscape(title)
                + "</title>\n<style>\n"
                + REPORT_HTML_STYLES
                + "\n</style>\n</head>\n<body>\n<header>\n<div class=\"page header-bar\"><div class=\"status\">"
                + statusHtml
                + "</div><button type=\"button\" id=\"theme-toggle\" aria-label=\"theme\">☀</button></div></header>\n<main class=\"page\">\n"
                + mainBodyHtml
                + "\n</main>\n<script>\n"
                + REPORT_HTML_THEME_SCRIPT
                + "\n"
                + REPORT_HTML_TEST_FILTER_SCRIPT
                + "\n"
                + REPORT_HTML_JUMP_SCRIPT
                + "\n</script>\n</body>\n</html>\n";
    }

    public static String diffRowWithId(
            String id, String cssClass, String oldNumber, String newNumber, String mark, String codeHtml) {
        String idAttribute = id.isEmpty() ? "" : " id=\"" + id + "\"";
        String classAttribute = cssClass.isEmpty() ? "row" : "row " + cssClass;
        return "<div class=\"" + classAttribute + "\"" + idAttribute + "><span class=\"rail run\">" + railRun(cssClass)
                + "</span><span class=\"no old\">" + oldNumber + "</span><span class=\"no new\">" + newNumber
                + "</span><span class=\"mark\">" + mark + "</span><span class=\"code\">" + codeHtml
                + "</span><span class=\"rail cov\">" + railCov(cssClass) + "</span></div>";
    }

    public static String diffRowHtml(
            String cssClass, String oldNumber, String newNumber, String mark, String codeHtml) {
        return diffRowWithId("", cssClass, oldNumber, newNumber, mark, codeHtml);
    }

    public static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String railRun(String cssClass) {
        if (classHas(cssClass, "column-labels")) {
            return "run";
        }
        if (classHas(cssClass, "fail-line")) {
            return "<button type=\"button\" class=\"flag bang\" aria-label=\"failed line\">!</button>";
        }
        return "";
    }

    private static String railCov(String cssClass) {
        return classHas(cssClass, "column-labels") ? "cov" : "";
    }

    private static boolean classHas(String cssClass, String name) {
        return com.cherrytest.common.rustcompat.RustText.splitWhitespace(cssClass)
                .contains(name);
    }

    private static String resource(String name) {
        try (InputStream stream = ReportShell.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("missing report shell resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
