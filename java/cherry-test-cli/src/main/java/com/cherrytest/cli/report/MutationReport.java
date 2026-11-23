package com.cherrytest.cli.report;

import com.cherrytest.cli.adapters.AdapterJson;
import com.cherrytest.cli.adapters.Formats;
import com.cherrytest.cli.adapters.Json;
import com.cherrytest.cli.adapters.Pitest;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

public final class MutationReport {
    private MutationReport() {}

    public static boolean isPitest(AdapterRequest request) {
        var tool = request.options().get("mutation_tool");
        return request.phase().equals("mutation")
                && tool != null
                && tool.isString()
                && tool.stringValue().equals("pitest");
    }

    public static String summary(JsonNode report) {
        if (report.path("status").asString().equals("no_changes"))
            return "PIT: no changed production sources; mutation testing not needed";
        JsonNode mutants = report.path("mutants");
        long detected = 0;
        var counts = new TreeMap<String, Integer>();
        for (JsonNode mutant : mutants) {
            if (mutant.path("detected").booleanValue()) detected++;
            counts.merge(mutant.path("status").asString(), 1, Integer::sum);
        }
        String statuses = String.join(
                ", ",
                counts.entrySet().stream()
                        .map(entry -> entry.getKey() + ": " + entry.getValue())
                        .toList());
        double score = mutants.isEmpty() ? 0.0 : detected * 100.0 / mutants.size();
        return "PIT: " + mutants.size() + " mutants in "
                + report.path("sources").size() + " changed source files; mutation score "
                + RustFloatFormat.fixed(score, 1) + "% (" + detected + "/" + mutants.size() + " detected); " + statuses;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String anchor(AdapterRequest request) {
        return "mutation-"
                + HexFormat.of()
                        .formatHex((request.project() + "/" + request.packageName()).getBytes(StandardCharsets.UTF_8));
    }

    static String index(Path reports) {
        StringBuilder rows = new StringBuilder();
        for (Path path : Formats.files(reports.resolve("adapters"), "json")) {
            if (!path.endsWith("result.json")) continue;
            Path output = path.getParent();
            if (output == null || !Files.isRegularFile(output.resolve("request.json"))) continue;
            AdapterRequest request;
            try {
                request = AdapterJson.parseRequest(Files.readAllBytes(output.resolve("request.json")));
            } catch (IOException exception) {
                throw new AnyhowError("read mutation request", exception);
            }
            if (!isPitest(request) || !Files.isRegularFile(output.resolve("mutation.json"))) continue;
            JsonNode report = Pitest.readReport(output.resolve("mutation.json"));
            rows.append("<li><a href=\"phases.html#")
                    .append(anchor(request))
                    .append("\">")
                    .append(escape(request.project()))
                    .append(" / ")
                    .append(escape(request.packageName()))
                    .append("</a>: ")
                    .append(escape(summary(report)))
                    .append("</li>");
        }
        if (rows.isEmpty()) return "";
        return "<section id=\"mutation\"><h2>Changed-source mutation testing</h2><p>Mutants are reported separately from test totals. The score is PIT's detected mutants divided by all reported mutants, including uncovered mutants.</p><ul>"
                + rows + "</ul></section>";
    }

    static String details(AdapterRequest request, JsonNode report, Path reports) {
        StringBuilder html = new StringBuilder("<div id=\"")
                .append(anchor(request))
                .append("\"><h3>PIT changed-source mutation testing</h3><p>")
                .append(escape(summary(report)))
                .append("</p>");
        if (!report.path("sources").isEmpty()) {
            html.append("<details><summary>Changed production sources</summary><ul>");
            for (JsonNode source : report.path("sources"))
                html.append("<li>").append(escape(source.stringValue())).append("</li>");
            html.append("</ul></details>");
        }
        for (List<String> link : List.of(
                List.of("mutation.json", "Mutation data"),
                List.of("pit-reports/mutations.xml", "PIT XML"),
                List.of("pit-reports/index.html", "PIT HTML"))) {
            Path file = request.output().resolve(link.get(0));
            if (Files.isRegularFile(file))
                html.append("<a href=\"")
                        .append(AdapterView.relativeLink(reports, file))
                        .append("\">")
                        .append(link.get(1))
                        .append("</a> ");
        }
        if (!report.path("mutants").isEmpty()) {
            html.append(
                    "<table><thead><tr><th>Status</th><th>Source</th><th>Method</th><th>Mutation</th><th>Killing test</th></tr></thead><tbody>");
            for (JsonNode mutant : report.path("mutants")) {
                html.append("<tr><td>")
                        .append(escape(mutant.path("status").asString()))
                        .append("</td><td>")
                        .append(escape(mutant.path("source").asString()))
                        .append(':')
                        .append(mutant.path("line").longValue())
                        .append("</td><td>")
                        .append(escape(mutant.path("class").asString()))
                        .append("::")
                        .append(escape(mutant.path("method").asString()))
                        .append("</td><td>")
                        .append(escape(mutant.path("description").asString()))
                        .append("<br><small>")
                        .append(escape(mutant.path("mutator").asString()))
                        .append("</small></td><td>")
                        .append(escape(
                                Json.index(mutant, "killing_test").isNull()
                                        ? "—"
                                        : mutant.path("killing_test").asString()))
                        .append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        return html.append("</div>").toString();
    }
}
