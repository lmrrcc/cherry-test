package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class NativeSelection {
    private final List<JsonNode> selectors;
    private final String sha256;
    private final Map<String, List<String>> selectedNames = new LinkedHashMap<>();

    private NativeSelection(List<JsonNode> selectors, String sha256) {
        this.selectors = selectors;
        this.sha256 = sha256;
    }

    static @Nullable NativeSelection load() {
        String configured = ProcessEnvironment.get("CHERRY_TEST_IDE_SELECTION");
        if (configured == null || configured.isBlank()) return null;
        return read(Path.of(configured));
    }

    static NativeSelection read(Path path) {
        try {
            if (Files.size(path) > 1024 * 1024) throw new AnyhowError("native selection exceeds 1 MiB");
            byte[] bytes = Files.readAllBytes(path);
            JsonNode root = Json.parseBytes(bytes);
            if (root.path("schemaVersion").asInt() != 1
                    || !root.path("selectors").isArray())
                throw new AnyhowError("native selection requires schemaVersion 1 and selectors array");
            List<JsonNode> selectors = new ArrayList<>();
            root.path("selectors").forEach(selectors::add);
            if (selectors.isEmpty() || selectors.size() > 1000)
                throw new AnyhowError("native selection requires 1..1000 selectors");
            return new NativeSelection(List.copyOf(selectors), NativeEvents.hashBytes(bytes));
        } catch (IOException error) {
            throw new AnyhowError("read native selection: " + error.getMessage());
        }
    }

    String sha256() {
        return sha256;
    }

    static ObjectNode selector(AdapterRequest request, String adapter, TestResult test, int occurrences) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(
                "kind",
                adapter.equals("maven") ? "junit-method" : adapter.equals("cargo") ? "rust-test" : "unavailable");
        node.put("value", test.name());
        node.put("phaseKey", NativeEvents.phaseKey(request));
        node.put("runtimeId", adapter);
        node.put("adapterId", adapter);
        node.put("manifest", request.manifest().toAbsolutePath().normalize().toString());
        String reason = reason(request, adapter, test.name());
        if (occurrences != 1) reason = "Reported name is not unique within the phase";
        node.put("exact", reason.isEmpty());
        node.put("reason", reason);
        return node;
    }

    private static String reason(AdapterRequest request, String adapter, String name) {
        if (adapter.equals("maven")) {
            if (!name.matches(
                    "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*::[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"))
                return "Parameterized, dynamic or display-name invocation cannot be selected exactly";
            if (Isolation.runsAsAClass(request.cwd(), name.substring(0, name.indexOf("::"))))
                return "Ordered/shared-instance class requires a broader class rerun";
            if (!request.phase().equals("unit") && !request.phase().equals("integration"))
                return "Maven phase has no verified test-selector property";
            if (request.options().containsKey("command"))
                return "Custom Maven command has no verified exact-selector contract";
            return "";
        }
        if (adapter.equals("cargo")) {
            return name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}:]*")
                    ? ""
                    : "Rust test name cannot be selected exactly";
        }
        return "Adapter has no verified exact-selector contract";
    }

    Plan apply(Plan plan) {
        Map<String, Plan.Job> jobs = new LinkedHashMap<>();
        plan.jobs().forEach(job -> jobs.put(NativeEvents.phaseKey(job.request()), job));
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (JsonNode selector : selectors) {
            String key = selector.path("phaseKey").asString();
            Plan.Job job = jobs.get(key);
            if (job == null) throw new AnyhowError("native selector phase is absent from the current configuration");
            String name = selector.path("value").asString();
            ObjectNode current = selector(
                    job.request(),
                    job.adapter(),
                    new TestResult(name, TestStatus.SKIPPED, 0, null, null, null, List.of()),
                    1);
            for (String field : List.of("kind", "value", "phaseKey", "runtimeId", "adapterId", "manifest", "exact")) {
                if (!current.path(field).equals(selector.path(field)))
                    throw new AnyhowError("native selector identity changed: " + field);
            }
            if (!current.path("exact").asBoolean())
                throw new AnyhowError(current.path("reason").asString());
            List<String> phaseNames = names.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!phaseNames.contains(name)) phaseNames.add(name);
        }
        List<Plan.PhasePlan> phases = new ArrayList<>();
        for (Plan.PhasePlan phase : plan.phases()) {
            List<Plan.Job> selected = new ArrayList<>();
            for (Plan.Job job : phase.jobs()) {
                List<String> tests = names.get(NativeEvents.phaseKey(job.request()));
                if (tests == null) continue;
                AdapterRequest req = job.request();
                List<String> args = new ArrayList<>(req.args());
                if (job.adapter().equals("maven")) {
                    String property = req.phase().equals("integration") ? "-Dit.test=" : "-Dtest=";
                    args.removeIf(arg -> arg.startsWith(property));
                    args.add(property
                            + String.join(
                                    ",",
                                    tests.stream()
                                            .map(name -> name.replace("::", "#"))
                                            .toList()));
                    args.add("-Dsurefire.failIfNoSpecifiedTests=true");
                    if (req.phase().equals("integration")) args.add("-Dit.failIfNoSpecifiedTests=true");
                } else {
                    if (tests.size() != 1)
                        throw new AnyhowError(
                                "Cargo exact rerun accepts one test per phase; use an explicit broader suite for several");
                    args.add(tests.getFirst());
                    args.add("--exact");
                }
                AdapterRequest narrowed = new AdapterRequest(
                        req.protocol(),
                        req.project(),
                        req.packageName(),
                        req.phase(),
                        req.root(),
                        req.manifest(),
                        req.cwd(),
                        req.output(),
                        req.execution(),
                        req.coverage(),
                        req.strict(),
                        args,
                        req.env(),
                        req.options(),
                        req.container(),
                        req.select());
                selected.add(new Plan.Job(
                        job.key(),
                        job.adapter(),
                        narrowed,
                        job.scopes(),
                        job.resources(),
                        job.estimateMs(),
                        job.exclusive(),
                        job.containerError(),
                        job.provisioned()));
            }
            if (!selected.isEmpty()) phases.add(new Plan.PhasePlan(phase.phase(), selected));
        }
        selectedNames.putAll(names);
        return new Plan(phases);
    }

    void validateResponse(AdapterRequest request, AdapterResponse response) {
        List<String> names = selectedNames.get(NativeEvents.phaseKey(request));
        if (names == null) throw new AnyhowError("executed phase was not selected");
        List<String> actual = response.tests().stream().map(TestResult::name).toList();
        if (actual.size() != names.size() || !new HashSet<>(actual).equals(new HashSet<>(names)))
            throw new AnyhowError("exact test selection was not honored: requested " + names + ", reported " + actual);
    }
}
