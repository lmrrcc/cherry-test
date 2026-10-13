package com.cherrytest.core;

import com.cherrytest.common.adapter.AdapterConfig;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class ConfigWriter {
    private ConfigWriter() {}

    public static void write(TestItConfig config) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> root = new ArrayList<>();
        List<Map.Entry<String, SerdeYamlWriter.Node>> adapters = new ArrayList<>();
        for (Map.Entry<String, AdapterConfig> adapter : config.adapters().entrySet()) {
            List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
            fields.add(entry("command", scalar(adapter.getValue().command())));
            fields.add(entry("args", strings(adapter.getValue().args())));
            fields.add(entry("timeout_secs", scalar(adapter.getValue().timeoutSecs())));
            adapters.add(entry(adapter.getKey(), new SerdeYamlWriter.Mapping(fields)));
        }
        root.add(entry("adapters", new SerdeYamlWriter.Mapping(adapters)));
        root.add(entry("defaults", defaults(config.defaults())));
        if (!config.auth().isEmpty()) {
            List<Map.Entry<String, SerdeYamlWriter.Node>> auth = new ArrayList<>();
            for (Map.Entry<String, AuthProfileConfig> profile : config.auth().entrySet()) {
                auth.add(entry(profile.getKey(), authProfile(profile.getValue())));
            }
            root.add(entry("auth", new SerdeYamlWriter.Mapping(auth)));
        }
        List<Map.Entry<String, SerdeYamlWriter.Node>> projects = new ArrayList<>();
        for (Map.Entry<String, ProjectConfig> project : config.projects().entrySet()) {
            projects.add(entry(project.getKey(), project(project.getValue())));
        }
        root.add(entry("projects", new SerdeYamlWriter.Mapping(projects)));
        root.add(entry("packages", new SerdeYamlWriter.Mapping(List.of())));
        List<Map.Entry<String, SerdeYamlWriter.Node>> scopes = new ArrayList<>();
        for (Map.Entry<String, ScopeConfig> scope : config.scopes().entrySet()) {
            List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
            fields.add(entry("projects", strings(scope.getValue().projects())));
            if (!scope.getValue().services().isEmpty()) {
                List<Map.Entry<String, SerdeYamlWriter.Node>> services = new ArrayList<>();
                scope.getValue().services().forEach((name, list) -> services.add(entry(name, strings(list))));
                fields.add(entry("services", new SerdeYamlWriter.Mapping(services)));
            }
            scopes.add(entry(scope.getKey(), new SerdeYamlWriter.Mapping(fields)));
        }
        root.add(entry("scopes", new SerdeYamlWriter.Mapping(scopes)));
        String yaml = SerdeYamlWriter.write(new SerdeYamlWriter.Mapping(root));
        try {
            Files.writeString(config.configPath(), yaml, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context("write " + config.configPath(), new AnyhowError(RustIoError.display(exception)));
        }
    }

    private static SerdeYamlWriter.Node defaults(Defaults defaults) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
        fields.add(entry("execution", scalar(defaults.execution().wireName())));
        fields.add(entry("strict", scalar(defaults.strict())));
        fields.add(entry("coverage", scalar(defaults.coverage())));
        fields.add(entry("html_report", scalar(defaults.htmlReport())));
        fields.add(entry("open_report", scalar(defaults.openReport())));
        fields.add(entry("runs_base", scalar(defaults.runsBase().toString())));
        if (!defaults.reportDir().equals(Path.of("target/cherry-test/reports"))) {
            fields.add(entry("report_dir", scalar(defaults.reportDir().toString())));
        }
        fields.add(entry("scope", scalar(defaults.scope())));
        fields.add(entry("diff_base", scalar(defaults.diffBase())));
        fields.add(entry("coverage_ignore", scalar(defaults.coverageIgnore().toString())));
        return new SerdeYamlWriter.Mapping(fields);
    }

    private static SerdeYamlWriter.Node authProfile(AuthProfileConfig profile) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
        fields.add(entry("profile", scalar(profile.profile())));
        fields.add(entry("issuer", scalar(profile.issuer())));
        fields.add(entry("client_id", scalar(profile.clientId())));
        fields.add(entry("client_secret", scalar(profile.clientSecret())));
        fields.add(entry("audience", scalar(profile.audience())));
        fields.add(entry("email", scalar(profile.email())));
        fields.add(entry("access_ttl_secs", scalar(profile.accessTtlSecs())));
        fields.add(entry("refresh_ttl_secs", scalar(profile.refreshTtlSecs())));
        if (profile.tokenUrl() != null) {
            fields.add(entry("token_url", scalar(profile.tokenUrl())));
        }
        fields.add(entry("cache_path", scalar(profile.cachePath().toString())));
        if (profile.oktaApiProject() != null) {
            fields.add(entry("okta_api_project", scalar(profile.oktaApiProject())));
        }
        if (profile.oktaApiService() != null) {
            fields.add(entry("okta_api_service", scalar(profile.oktaApiService())));
        }
        return new SerdeYamlWriter.Mapping(fields);
    }

    private static SerdeYamlWriter.Node project(ProjectConfig project) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
        if (project.path() != null) {
            fields.add(entry("path", scalar(project.path())));
        }
        if (project.diffBase() != null) {
            fields.add(entry("diff_base", scalar(project.diffBase())));
        }
        if (project.compose() != null) {
            fields.add(entry("compose", scalar(project.compose())));
        }
        if (!project.services().isEmpty()) {
            List<Map.Entry<String, SerdeYamlWriter.Node>> services = new ArrayList<>();
            for (Map.Entry<String, ServiceConfig> service : project.services().entrySet()) {
                List<Map.Entry<String, SerdeYamlWriter.Node>> serviceFields = new ArrayList<>();
                serviceFields.add(entry("service", scalar(service.getValue().service())));
                serviceFields.add(entry("port", scalar((long) service.getValue().port())));
                if (service.getValue().health() != null) {
                    serviceFields.add(entry("health", scalar(service.getValue().health())));
                }
                if (service.getValue().kind() != null) {
                    serviceFields.add(entry("kind", scalar(service.getValue().kind())));
                }
                services.add(entry(service.getKey(), new SerdeYamlWriter.Mapping(serviceFields)));
            }
            fields.add(entry("services", new SerdeYamlWriter.Mapping(services)));
        }
        if (!project.packages().isEmpty()) {
            List<Map.Entry<String, SerdeYamlWriter.Node>> packages = new ArrayList<>();
            for (Map.Entry<String, PackageConfig> packageEntry :
                    project.packages().entrySet()) {
                packages.add(entry(packageEntry.getKey(), packageConfig(packageEntry.getValue())));
            }
            fields.add(entry("packages", new SerdeYamlWriter.Mapping(packages)));
        }
        if (project.functional() != null) {
            fields.add(entry("functional", runner(project.functional())));
        }
        if (project.ui() != null) {
            fields.add(entry("ui", runner(project.ui())));
        }
        return new SerdeYamlWriter.Mapping(fields);
    }

    private static SerdeYamlWriter.Node packageConfig(PackageConfig config) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
        fields.add(entry("adapter", scalar(config.adapter())));
        fields.add(entry(
                "execution",
                scalar(config.execution() == null ? null : config.execution().wireName())));
        fields.add(entry("options", options(config.options())));
        fields.add(entry("integration", optionalRunner(config.integration())));
        fields.add(entry("visual", optionalRunner(config.visual())));
        if (config.runtime() != PackageRuntime.RUST) {
            fields.add(entry("runtime", scalar(config.runtime().wireName())));
        }
        if (!config.kinds().isEmpty()) {
            fields.add(entry("kinds", strings(config.kinds())));
        }
        if (config.manifest() != null) {
            fields.add(entry("manifest", scalar(config.manifest().toString())));
        }
        if (config.unit() != null) {
            fields.add(entry("unit", runner(config.unit())));
        }
        if (config.functional() != null) {
            fields.add(entry("functional", runner(config.functional())));
        }
        if (config.ui() != null) {
            fields.add(entry("ui", runner(config.ui())));
        }
        if (config.mutation() != null) {
            fields.add(entry("mutation", runner(config.mutation())));
        }
        return new SerdeYamlWriter.Mapping(fields);
    }

    private static SerdeYamlWriter.Node optionalRunner(@Nullable ExternalRunner runner) {
        return runner == null ? scalar(null) : runner(runner);
    }

    private static SerdeYamlWriter.Node runner(ExternalRunner runner) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
        fields.add(entry("adapter", scalar(runner.adapter())));
        fields.add(entry("scope", scalar(runner.scope())));
        fields.add(entry("options", options(runner.options())));
        fields.add(entry("command", scalar(runner.command())));
        if (!runner.args().isEmpty()) {
            fields.add(entry("args", strings(runner.args())));
        }
        if (runner.cwd() != null) {
            fields.add(entry("cwd", scalar(runner.cwd().toString())));
        }
        return new SerdeYamlWriter.Mapping(fields);
    }

    private static SerdeYamlWriter.Node options(Map<String, JsonNode> options) {
        List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
        options.forEach((key, value) -> fields.add(entry(key, json(value))));
        return new SerdeYamlWriter.Mapping(fields);
    }

    private static SerdeYamlWriter.Node json(JsonNode value) {
        if (value.isObject()) {
            List<Map.Entry<String, SerdeYamlWriter.Node>> fields = new ArrayList<>();
            value.properties().forEach(property -> fields.add(entry(property.getKey(), json(property.getValue()))));
            return new SerdeYamlWriter.Mapping(fields);
        }
        if (value.isArray()) {
            List<SerdeYamlWriter.Node> items = new ArrayList<>();
            value.forEach(item -> items.add(json(item)));
            return new SerdeYamlWriter.Sequence(items);
        }
        if (value.isNull()) {
            return scalar(null);
        }
        if (value.isBoolean()) {
            return scalar(value.booleanValue());
        }
        if (value.isIntegralNumber()) {
            return scalar(value.bigIntegerValue());
        }
        if (value.isNumber()) {
            return scalar(value.doubleValue());
        }
        return scalar(value.asString());
    }

    private static SerdeYamlWriter.Node strings(List<String> values) {
        return new SerdeYamlWriter.Sequence(
                values.stream().map(ConfigWriter::scalar).toList());
    }

    private static SerdeYamlWriter.Node scalar(@Nullable Object value) {
        return new SerdeYamlWriter.Scalar(value);
    }

    private static Map.Entry<String, SerdeYamlWriter.Node> entry(String key, SerdeYamlWriter.Node value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
