package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class MutationReportIsolationTest {
    private static final String ORDINARY =
            "{\"project\":\"demo\",\"suite\":\"http\",\"test\":\"health\",\"full_name\":\"demo_project_http_suite_health_test\",\"status\":200,\"passed\":true,\"elapsed_ms\":3}\n";
    private static final String MUTATED =
            "{\"project\":\"demo\",\"suite\":\"http\",\"test\":\"health\",\"full_name\":\"demo_project_http_suite_health_test\",\"status\":500,\"passed\":false,\"elapsed_ms\":999}\n";

    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({
        "1,mutation,true,true",
        "1,mutation,true,false",
        "2,mutation,true,true",
        "2,mutation,true,false",
        "1,mutation,false,true",
        "1,mutation,false,false",
        "2,mutation,false,true",
        "2,mutation,false,false",
        "1,unit,false,true",
        "1,unit,false,false",
        "2,unit,false,true",
        "2,unit,false,false"
    })
    void keeps_pit_test_logs_private_without_changing_other_adapters(
            int workers, String phase, boolean pitest, boolean success) throws IOException {
        Files.writeString(root.resolve("cherry-test.yaml"), "projects: {}\n");
        TestItConfig config = TestItConfig.load(root);
        RunDir run = RunDir.create(root, Path.of("runs"), RunIdGenerator.systemClock());
        Path aggregate = run.reports().resolve("integration/api.jsonl");
        Files.createDirectories(aggregate.getParent());
        Files.writeString(aggregate, ORDINARY);
        Path script = root.resolve("adapter.sh");
        Files.writeString(
                script, """
                /bin/mkdir -p "$CHERRY_TEST_REPORT_DIR/integration"
                printf '%s' 'MUTATED_RECORD' > "$CHERRY_TEST_REPORT_DIR/integration/api.jsonl"
                printf '%s' '{"tool":"pitest","status":"complete","sources":["src/main/java/Demo.java"],"mutants":[{"status":"KILLED","detected":true,"source":"src/main/java/Demo.java","line":1,"class":"Demo","method":"value","mutator":"returns","description":"replaced return","killing_test":"DemoTest"}]}' > "$CHERRY_TEST_REPORT_DIR/../mutation.json"
                printf '%s' "$CHERRY_TEST_RUN_DIR" > "$CHERRY_TEST_REPORT_DIR/run-dir.txt"
                printf '%s' '{"protocol":1,"success":RESULT_SUCCESS,"tests":[]}'
                """.replace("MUTATED_RECORD", MUTATED).replace("RESULT_SUCCESS", Boolean.toString(success)));
        var options = new Runner.RunOptions(
                root,
                run,
                config,
                false,
                true,
                Runner.TestListing.AFTER_EACH_PHASE,
                List.of(),
                List.of(phase),
                (name, cwd, stdin) ->
                        new ProcessRunner.Spec("/bin/sh", List.of(script.toString()), Map.of(), cwd, stdin),
                Map.of("CHERRY_TEST_RUN_DIR", run.root().toString()),
                new Scheduler.Parallelism(workers, new Plan.Resources(Long.MAX_VALUE, Long.MAX_VALUE), false));
        Path output = run.reports().resolve("adapters/demo/api/" + phase);
        var requestOptions = new TreeMap<String, JsonNode>();
        if (pitest) requestOptions.put("mutation_tool", JsonNodeFactory.instance.stringNode("pitest"));
        var request = new AdapterRequest(
                1,
                "demo",
                "api",
                phase,
                root,
                root.resolve("pom.xml"),
                root,
                output,
                ExecutionMode.LOCAL,
                false,
                pitest,
                List.of(),
                new TreeMap<>(),
                requestOptions);
        var job = new Plan.Job(
                new Plan.JobKey("demo", "api", phase),
                pitest ? "pitest" : "command",
                request,
                List.of(),
                Plan.Resources.PACKAGE_DEFAULT,
                0,
                false,
                null,
                List.of());
        var plan = new Plan(List.of(new Plan.PhasePlan(phase, List.of(job))));
        var results = Scheduler.run(
                plan, List.of(), options, new PreflightPlan(false, List.of(), List.of(), List.of(), List.of()));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome().success()).isEqualTo(success);
        assertThat(Files.readString(aggregate)).isEqualTo(pitest ? ORDINARY : ORDINARY + MUTATED);
        assertThat(output.resolve("mutation.json")).isRegularFile();
        assertThat(output.resolve("result.json")).isRegularFile();
        Path privateReports = Runner.jobReportDir(request);
        if (pitest) {
            assertThat(Files.readString(privateReports.resolve("integration/api.jsonl")))
                    .isEqualTo(MUTATED);
            assertThat(Files.readString(privateReports.resolve("run-dir.txt")))
                    .isEqualTo(run.root().toString());
        } else {
            assertThat(privateReports).doesNotExist();
        }
    }
}
