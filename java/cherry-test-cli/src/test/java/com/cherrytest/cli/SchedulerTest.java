package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.core.Jobs;
import com.cherrytest.core.ParallelConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulerTest {
    private static SortedMap<String, String> vars(String... pairs) {
        SortedMap<String, String> variables = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            variables.put(pairs[index], pairs[index + 1]);
        }
        return variables;
    }

    @Test
    void job_env_layers_phase_project_file_services_and_auth() {
        SortedMap<String, String> base = vars("PATH", "/bin", "CHERRY_TEST_RUN_ID", "run");
        Scheduler.EnvLayers layers = new Scheduler.EnvLayers(
                base,
                List.of(
                        Map.entry("mini", vars("CHERRY_TEST_MINI_DB_NAME", "mini")),
                        Map.entry("shop", vars("CHERRY_TEST_SHOP_DB_NAME", "shop"))));
        Scheduler.RunState state = new Scheduler.RunState(PlanTest.emptyPreflight());
        state.serviceEnv.put("CHERRY_TEST_MINI_API_URL", "http://127.0.0.1:4100");
        state.authEnv.put("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token");
        SortedMap<String, String> environment = layers.jobEnv(state, "integration", "mini");
        assertThat(environment)
                .containsEntry("CHERRY_TEST_PHASE", "integration")
                .containsEntry("CHERRY_TEST_MINI_DB_NAME", "mini")
                .doesNotContainKey("CHERRY_TEST_SHOP_DB_NAME")
                .containsEntry("CHERRY_TEST_MINI_API_URL", "http://127.0.0.1:4100")
                .containsEntry("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token")
                .containsEntry("PATH", "/bin");
        SortedMap<String, String> phase = layers.phaseEnv(state, "integration");
        assertThat(phase)
                .containsEntry("CHERRY_TEST_SHOP_DB_NAME", "shop")
                .containsEntry("CHERRY_TEST_MINI_DB_NAME", "mini");
        assertThat(base).hasSize(2);
    }

    private static Scheduler.Candidate job(int slot, long cpus, boolean exclusive) {
        return new Scheduler.Candidate(slot, new Plan.Resources(cpus * 1000, 1L << 30), exclusive);
    }

    private static Plan.Resources budget(long cpus) {
        return new Plan.Resources(cpus * 1000, 64L << 30);
    }

    private record Timed(int slot, long start, long end) {}

    /** Runs {@code jobs} (cpus, exclusive, duration) through {@code nextStart} in simulated time. */
    private static List<Timed> simulate(List<long[]> jobs, long cpus, int workers) {
        List<Scheduler.Candidate> pending = new ArrayList<>();
        for (int slot = 0; slot < jobs.size(); slot++) {
            pending.add(job(slot, jobs.get(slot)[0], jobs.get(slot)[1] == 1));
        }
        List<Map.Entry<Scheduler.Candidate, Long>> running = new ArrayList<>();
        List<Timed> timeline = new ArrayList<>();
        long now = 0;
        int[] skips = {0};
        while (!pending.isEmpty() || !running.isEmpty()) {
            while (true) {
                List<Scheduler.Candidate> busy =
                        running.stream().map(Map.Entry::getKey).toList();
                Integer next = Scheduler.nextStart(pending, busy, budget(cpus), workers, skips);
                if (next == null) {
                    break;
                }
                Scheduler.Candidate started = pending.remove((int) next);
                long end = now + jobs.get(started.slot())[2];
                timeline.add(new Timed(started.slot(), now, end));
                running.add(Map.entry(started, end));
                long used = running.stream()
                        .mapToLong(entry -> entry.getKey().resources().millicpus())
                        .sum();
                assertThat(running.size() == 1 || used <= cpus * 1000)
                        .as("budget exceeded")
                        .isTrue();
                assertThat(running.size()).isLessThanOrEqualTo(workers);
            }
            if (running.isEmpty()) {
                break;
            }
            long firstEnd =
                    running.stream().mapToLong(Map.Entry::getValue).min().orElseThrow();
            now = firstEnd;
            long current = now;
            running.removeIf(entry -> entry.getValue() <= current);
        }
        timeline.sort(Comparator.comparingInt(Timed::slot).thenComparingLong(Timed::start));
        return timeline;
    }

    @Test
    void next_start_takes_the_head_when_it_fits() {
        List<Scheduler.Candidate> pending = List.of(job(0, 2, false), job(1, 1, false));
        int[] skips = {2};
        assertThat(Scheduler.nextStart(pending, List.of(), budget(4), 4, skips)).isZero();
        assertThat(skips[0]).isZero();
        assertThat(Scheduler.nextStart(pending, List.of(job(9, 1, false)), budget(4), 1, skips))
                .isNull();
        assertThat(Scheduler.nextStart(List.of(), List.of(), budget(4), 4, skips))
                .isNull();
    }

    @Test
    void a_smaller_job_starts_ahead_until_the_head_reserves() {
        Scheduler.Candidate head = job(0, 2, false);
        List<Scheduler.Candidate> smalls = new ArrayList<>();
        for (int slot = 1; slot <= 5; slot++) {
            smalls.add(job(slot, 1, false));
        }
        Scheduler.Candidate big = job(9, 3, false);
        List<Scheduler.Candidate> pending = new ArrayList<>(List.of(head));
        pending.addAll(smalls);
        int[] skips = {0};
        assertThat(Scheduler.nextStart(pending, List.of(big), budget(4), 4, skips))
                .isEqualTo(1);
        assertThat(skips[0]).isEqualTo(1);
        pending.remove(1);
        assertThat(Scheduler.nextStart(pending, List.of(big, smalls.getFirst()), budget(4), 4, skips))
                .isNull();
        assertThat(skips[0]).isEqualTo(1);
        assertThat(Scheduler.nextStart(pending, List.of(big), budget(4), 4, skips))
                .isEqualTo(1);
        pending.remove(1);
        assertThat(Scheduler.nextStart(pending, List.of(big), budget(4), 4, skips))
                .isEqualTo(1);
        pending.remove(1);
        assertThat(skips[0]).isEqualTo(Scheduler.HEAD_SKIP_LIMIT);
        assertThat(Scheduler.nextStart(pending, List.of(big), budget(4), 4, skips))
                .isNull();
        assertThat(Scheduler.nextStart(pending, List.of(), budget(4), 4, skips)).isZero();
        assertThat(skips[0]).isZero();
    }

    @Test
    void exclusive_and_oversized_jobs_run_alone() {
        int[] skips = {0};
        Scheduler.Candidate exclusive = job(0, 1, true);
        assertThat(Scheduler.nextStart(List.of(exclusive), List.of(job(5, 1, false)), budget(8), 8, skips))
                .isNull();
        assertThat(Scheduler.nextStart(List.of(exclusive), List.of(), budget(8), 8, skips))
                .isZero();
        assertThat(Scheduler.nextStart(List.of(job(1, 1, false)), List.of(exclusive), budget(8), 8, skips))
                .isNull();
        Scheduler.Candidate oversized = job(2, 16, false);
        assertThat(Scheduler.nextStart(List.of(oversized), List.of(), budget(8), 8, skips))
                .isZero();
        assertThat(Scheduler.nextStart(List.of(job(3, 1, false)), List.of(oversized), budget(8), 8, skips))
                .isNull();
        assertThat(simulate(List.of(new long[] {1, 0, 10}, new long[] {1, 1, 10}, new long[] {1, 0, 10}), 8, 8))
                .containsExactly(new Timed(0, 0, 10), new Timed(1, 10, 20), new Timed(2, 0, 10));
    }

    @Test
    void simulated_runs_stay_within_the_budget_and_never_starve_the_head() {
        List<long[]> jobs = new ArrayList<>(List.of(new long[] {3, 0, 50}, new long[] {4, 0, 40}));
        for (int small = 0; small < 6; small++) {
            jobs.add(new long[] {1, 0, 10});
        }
        List<Timed> timeline = simulate(jobs, 4, 4);
        assertThat(timeline).hasSize(jobs.size());
        Timed head =
                timeline.stream().filter(timed -> timed.slot() == 1).findFirst().orElseThrow();
        assertThat(head.start()).isEqualTo(50);
        long beforeHead = timeline.stream()
                .filter(timed -> timed.slot() >= 2 && timed.start() < 50)
                .count();
        assertThat(beforeHead).isEqualTo(Scheduler.HEAD_SKIP_LIMIT);
        long clock = 0;
        for (Timed timed : simulate(jobs, 4, 1)) {
            assertThat(timed.start()).as("job " + timed.slot()).isEqualTo(clock);
            clock = timed.end();
        }
    }

    @Test
    void orders_jobs_longest_first_with_configuration_order_on_ties(@TempDir Path root) throws IOException {
        Runner.RunOptions options = PlanTest.options(root, PlanTest.config(root, PlanTest.FIXTURE), List.of());
        Plan plan = Plan.plan(PlanTest.projects(), options, PlanTest.emptyPreflight());
        long[] estimates = {100, 300, 300};
        List<Map.Entry<Integer, Plan.Job>> jobs = new ArrayList<>();
        List<Plan.Job> planned = plan.jobs().toList();
        for (int index = 0; index < planned.size(); index++) {
            Plan.Job job = planned.get(index);
            jobs.add(Map.entry(
                    index,
                    new Plan.Job(
                            job.key(),
                            job.adapter(),
                            job.request(),
                            job.scopes(),
                            job.resources(),
                            estimates[index],
                            job.exclusive())));
        }
        assertThat(Scheduler.longestFirst(jobs)).containsExactly(1, 2, 0);
    }

    @Test
    void one_stage_per_phase_unless_the_barrier_is_off(@TempDir Path root) throws IOException {
        Runner.RunOptions options = PlanTest.options(root, PlanTest.config(root, PlanTest.FIXTURE), List.of());
        Plan plan = Plan.plan(PlanTest.projects(), options, PlanTest.emptyPreflight());
        Plan.Resources unlimited = Scheduler.Parallelism.SEQUENTIAL.budget();
        List<Scheduler.Stage> barrier = Scheduler.stages(plan, new Scheduler.Parallelism(4, unlimited, true));
        assertThat(barrier).hasSize(plan.phases().size());
        assertThat(barrier.get(1).jobs().getFirst().getKey()).isEqualTo(2);
        List<Scheduler.Stage> merged = Scheduler.stages(plan, new Scheduler.Parallelism(4, unlimited, false));
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().lastPhase()).isEqualTo("visual");
        assertThat(merged.getFirst().jobs().stream().map(Map.Entry::getKey)).containsExactly(0, 1, 2);
        List<Scheduler.Stage> sequential = Scheduler.stages(plan, new Scheduler.Parallelism(1, unlimited, false));
        assertThat(sequential).hasSize(plan.phases().size());
    }

    @Test
    void resolve_keeps_one_job_unless_asked_and_honours_the_configured_budget() {
        Map<String, String> environment = Map.of();
        assertThat(Scheduler.Parallelism.resolve(null, null, ExecutionMode.CONTAINER, environment))
                .isEqualTo(Scheduler.Parallelism.SEQUENTIAL);
        ParallelConfig config = new ParallelConfig(Jobs.AUTO, 6500L, 8L << 30, false);
        Scheduler.Parallelism auto = Scheduler.Parallelism.resolve(null, config, ExecutionMode.CONTAINER, environment);
        assertThat(auto.workers()).isEqualTo(6);
        assertThat(auto.budget()).isEqualTo(new Plan.Resources(6500, 8L << 30));
        assertThat(auto.phaseBarrier()).isFalse();
        assertThat(Scheduler.Parallelism.resolve(Jobs.count(3), config, ExecutionMode.LOCAL, environment)
                        .workers())
                .isEqualTo(3);
        assertThat(Scheduler.Parallelism.resolve(Jobs.count(1), config, ExecutionMode.LOCAL, environment))
                .isEqualTo(Scheduler.Parallelism.SEQUENTIAL);
        Scheduler.Parallelism host =
                Scheduler.Parallelism.resolve(Jobs.count(2), null, ExecutionMode.LOCAL, environment);
        assertThat(host.budget().memory()).isEqualTo(Long.MAX_VALUE);
        assertThat(host.budget().millicpus()).isGreaterThanOrEqualTo(1000);
        assertThat(host.phaseBarrier()).isTrue();
    }

    @Test
    void parses_docker_info_resources() {
        assertThat(Scheduler.parseDockerResources("10 8321515520\n"))
                .isEqualTo(new Plan.Resources(10_000, 8_321_515_520L));
        assertThat(Scheduler.parseDockerResources("")).isNull();
        assertThat(Scheduler.parseDockerResources("0 10")).isNull();
        assertThat(Scheduler.parseDockerResources("four 10")).isNull();
    }
}
