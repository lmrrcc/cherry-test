package com.cherrytest.cli;

import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.cli.report.MutationReport;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.Jobs;
import com.cherrytest.core.ParallelConfig;
import com.cherrytest.core.Workspace;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.Nullable;

/** Executes a {@link Plan}: boots scopes, builds each job's environment and runs the jobs. */
public final class Scheduler {
    /** Times another job may start ahead of the queue's head before the head reserves the next free capacity. */
    public static final int HEAD_SKIP_LIMIT = 3;

    private Scheduler() {}

    /** How many jobs run at once, the CPU and memory they share, and whether phases wait for each other. */
    public record Parallelism(int workers, Plan.Resources budget, boolean phaseBarrier) {
        public static final Parallelism SEQUENTIAL =
                new Parallelism(1, new Plan.Resources(Long.MAX_VALUE, Long.MAX_VALUE), true);

        /**
         * Resolves {@code --jobs} over {@code defaults.parallel.jobs} (default 1). One job keeps today's sequential run
         * and asks nothing of the host. Otherwise the budget comes from {@code defaults.parallel}, then from {@code
         * docker info} when execution is container, then from the host's CPUs with no memory limit; {@code auto}
         * starts one job per budgeted CPU.
         */
        public static Parallelism resolve(
                @Nullable Jobs cli,
                @Nullable ParallelConfig config,
                ExecutionMode execution,
                Map<String, String> environment) {
            Jobs jobs = cli != null ? cli : config != null && config.jobs() != null ? config.jobs() : Jobs.count(1);
            if (jobs.equals(Jobs.count(1))) {
                return SEQUENTIAL;
            }
            Long cpus = config == null ? null : config.millicpus();
            Long memory = config == null ? null : config.memory();
            Plan.Resources docker = execution == ExecutionMode.CONTAINER && (cpus == null || memory == null)
                    ? dockerResources(environment)
                    : null;
            long hostCpus = Runtime.getRuntime().availableProcessors();
            Plan.Resources budget = new Plan.Resources(
                    cpus != null ? cpus : docker != null ? docker.millicpus() : hostCpus * 1000,
                    memory != null ? memory : docker != null ? docker.memory() : Long.MAX_VALUE);
            int workers = jobs.auto() ? (int) Math.max(1, budget.millicpus() / 1000) : (int) jobs.count();
            return new Parallelism(workers, budget, config == null || config.phaseBarrier());
        }
    }

    private static Plan.@Nullable Resources dockerResources(Map<String, String> environment) {
        try {
            CommandOutput output = SystemCommand.of("docker")
                    .environment(environment)
                    .args("info", "--format", "{{.NCPU}} {{.MemTotal}}")
                    .output();
            return output.success() ? parseDockerResources(output.stdoutLossy()) : null;
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    static Plan.@Nullable Resources parseDockerResources(String text) {
        List<String> fields = new ArrayList<>();
        for (String field : RustText.trim(text).split("\\s+", -1)) {
            if (!field.isEmpty()) {
                fields.add(field);
            }
        }
        if (fields.size() < 2) {
            return null;
        }
        try {
            long cpus = Long.parseLong(fields.get(0));
            long memory = Long.parseLong(fields.get(1));
            return cpus > 0 && memory > 0 ? new Plan.Resources(cpus * 1000, memory) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** A job waiting for, or holding, a share of the budget. */
    public record Candidate(int slot, Plan.Resources resources, boolean exclusive) {}

    /**
     * Picks the pending job to start next, or {@code null} when nothing may start now. The head of {@code pending}
     * starts when it fits. Otherwise the first smaller job that fits starts in its place, until the head has been
     * passed over {@link #HEAD_SKIP_LIMIT} times; from then on the head reserves the capacity and nothing else starts
     * until it does. An exclusive job starts only on an idle scheduler and runs alone; a job larger than the whole
     * budget also starts only when nothing else runs. {@code headSkips[0]} carries the skip count between calls.
     */
    public static @Nullable Integer nextStart(
            List<Candidate> pending, List<Candidate> running, Plan.Resources budget, int workers, int[] headSkips) {
        if (pending.isEmpty() || running.size() >= Math.max(workers, 1)) {
            return null;
        }
        if (running.stream().anyMatch(Candidate::exclusive)) {
            return null;
        }
        long usedCpus = 0;
        long usedMemory = 0;
        for (Candidate job : running) {
            usedCpus = saturatingAdd(usedCpus, job.resources().millicpus());
            usedMemory = saturatingAdd(usedMemory, job.resources().memory());
        }
        if (fits(pending.getFirst(), running, budget, usedCpus, usedMemory)) {
            headSkips[0] = 0;
            return 0;
        }
        if (headSkips[0] >= HEAD_SKIP_LIMIT) {
            return null;
        }
        for (int index = 1; index < pending.size(); index++) {
            if (fits(pending.get(index), running, budget, usedCpus, usedMemory)) {
                headSkips[0]++;
                return index;
            }
        }
        return null;
    }

    private static boolean fits(
            Candidate job, List<Candidate> running, Plan.Resources budget, long usedCpus, long usedMemory) {
        return running.isEmpty()
                || (!job.exclusive()
                        && saturatingAdd(usedCpus, job.resources().millicpus()) <= budget.millicpus()
                        && saturatingAdd(usedMemory, job.resources().memory()) <= budget.memory());
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        return ((left ^ sum) & (right ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** What a run accumulates while it executes. The run owns it; nothing here is process-global. */
    public static final class RunState {
        final Workspace.ScopeState scopes = new Workspace.ScopeState();
        final PreflightPlan preflight;
        final SortedMap<String, String> serviceEnv = new TreeMap<>(RustOrdering.STRING_ORDER);
        final SortedMap<String, String> authEnv = new TreeMap<>(RustOrdering.STRING_ORDER);
        private final TreeSet<String> phaseBooted = new TreeSet<>(RustOrdering.STRING_ORDER);
        /** Scopes whose Compose services this phase started for jobs that run their image services themselves. */
        private final TreeSet<String> phaseCompose = new TreeSet<>(RustOrdering.STRING_ORDER);

        private boolean authReady;

        public RunState(PreflightPlan preflight) {
            this.preflight = preflight;
        }

        void startPhase() {
            phaseBooted.clear();
            phaseCompose.clear();
            authReady = false;
        }
    }

    /** The base environment and every selected project's {@code .env.test} variables, in configuration order. */
    public record EnvLayers(Map<String, String> base, List<Map.Entry<String, SortedMap<String, String>>> envFiles) {
        public EnvLayers {
            envFiles = List.copyOf(envFiles);
        }

        static EnvLayers load(Runner.RunOptions options, List<ProjectGroup> projects) {
            List<Map.Entry<String, SortedMap<String, String>>> files = new ArrayList<>();
            for (ProjectGroup project : projects) {
                files.add(Map.entry(project.id(), RunEnv.envFile(options.workspaceRoot(), project.id())));
            }
            return new EnvLayers(options.env(), files);
        }

        /** What a job's adapter receives: the base, its phase, its project's {@code .env.test}, then service and auth variables. */
        SortedMap<String, String> jobEnv(RunState state, String phase, String project) {
            SortedMap<String, String> environment = new TreeMap<>(RustOrdering.STRING_ORDER);
            environment.putAll(base);
            environment.put("CHERRY_TEST_PHASE", phase);
            for (Map.Entry<String, SortedMap<String, String>> file : envFiles) {
                if (file.getKey().equals(project)) {
                    environment.putAll(file.getValue());
                    break;
                }
            }
            environment.putAll(state.serviceEnv);
            environment.putAll(state.authEnv);
            return environment;
        }

        /** What Docker Compose and the auth preflight receive during a phase: every project's {@code .env.test} applies. */
        SortedMap<String, String> phaseEnv(RunState state, String phase) {
            SortedMap<String, String> environment = new TreeMap<>(RustOrdering.STRING_ORDER);
            environment.putAll(base);
            environment.put("CHERRY_TEST_PHASE", phase);
            for (Map.Entry<String, SortedMap<String, String>> file : envFiles) {
                environment.putAll(file.getValue());
            }
            environment.putAll(state.serviceEnv);
            environment.putAll(state.authEnv);
            return environment;
        }
    }

    /** A finished job and its position in the plan. */
    public record Finished(int order, Runner.JobOutcome outcome) {}

    /** Jobs that share one boot and teardown of scopes: a phase, or the whole run without a phase barrier. */
    record Stage(String lastPhase, List<Map.Entry<Integer, Plan.Job>> jobs) {}

    static List<Stage> stages(Plan plan, Parallelism parallelism) {
        List<Stage> stages = new ArrayList<>();
        int position = 0;
        boolean merge = parallelism.workers() > 1 && !parallelism.phaseBarrier();
        for (Plan.PhasePlan phase : plan.phases()) {
            List<Map.Entry<Integer, Plan.Job>> jobs = new ArrayList<>();
            for (Plan.Job job : phase.jobs()) {
                jobs.add(Map.entry(position++, job));
            }
            if (merge
                    && !stages.isEmpty()
                    && !phase.phase().equals("mutation")
                    && !stages.getLast().lastPhase().equals("mutation")) {
                Stage last = stages.removeLast();
                List<Map.Entry<Integer, Plan.Job>> merged = new ArrayList<>(last.jobs());
                merged.addAll(jobs);
                stages.add(new Stage(phase.phase(), merged));
            } else {
                stages.add(new Stage(phase.phase(), jobs));
            }
        }
        return stages;
    }

    /**
     * Executes the plan and returns each finished job with its position in the plan. With one worker, jobs run one at
     * a time in plan order and scopes boot right before the first job that needs them. With more workers, every scope
     * of a stage boots first, sequentially and in plan order, then jobs start longest first within the budget. Scopes
     * stop at the end of each stage; a stage is one phase unless the phase barrier is off.
     */
    public static List<Finished> run(
            Plan plan, List<ProjectGroup> projects, Runner.RunOptions options, PreflightPlan preflight) {
        EnvLayers layers = EnvLayers.load(options, projects);
        RunState state = new RunState(preflight.copy());
        List<Finished> finished = new ArrayList<>();
        for (Stage stage : stages(plan, options.parallelism())) {
            state.startPhase();
            if (options.parallelism().workers() <= 1) {
                for (Map.Entry<Integer, Plan.Job> entry : stage.jobs()) {
                    if (ProcessRunner.cancelled()) {
                        continue;
                    }
                    Plan.Job job = entry.getValue();
                    String setup = setupJob(job, state, layers, options);
                    SortedMap<String, String> environment =
                            layers.jobEnv(state, job.key().phase(), job.key().project());
                    Runner.JobOutcome outcome = Runner.executeJob(job, environment, state.preflight, setup, options);
                    if (!MutationReport.isPitest(job.request()))
                        options.runDir().mergeJobReport(Runner.jobReportDir(job.request()));
                    finished.add(new Finished(entry.getKey(), outcome));
                }
            } else {
                List<Prepared> prepared = new ArrayList<>();
                for (Map.Entry<Integer, Plan.Job> entry : stage.jobs()) {
                    if (ProcessRunner.cancelled()) {
                        break;
                    }
                    String setup = setupJob(entry.getValue(), state, layers, options);
                    prepared.add(new Prepared(entry.getKey(), entry.getValue(), setup));
                }
                List<Finished> stageFinished = new ArrayList<>(runParallel(prepared, state, layers, options));
                stageFinished.sort(Comparator.comparingInt(Finished::order));
                for (Finished done : stageFinished) {
                    for (Prepared item : prepared) {
                        if (item.position() == done.order()
                                && !MutationReport.isPitest(item.job().request())) {
                            options.runDir()
                                    .mergeJobReport(
                                            Runner.jobReportDir(item.job().request()));
                        }
                    }
                }
                finished.addAll(stageFinished);
            }
            Workspace.teardownScopes(state.scopes, layers.phaseEnv(state, stage.lastPhase()));
            if (ProcessRunner.cancelled()) {
                break;
            }
        }
        return finished;
    }

    /** A job whose scopes were booted before the stage started, with the error of that setup, if any. */
    private record Prepared(
            int position, Plan.Job job, @Nullable String setup) {}

    /**
     * Runs prepared jobs on virtual threads within the budget and returns them as they finish. After the first job
     * error no new job starts; running jobs finish and the error is rethrown.
     */
    private static List<Finished> runParallel(
            List<Prepared> prepared, RunState state, EnvLayers layers, Runner.RunOptions options) {
        List<Map.Entry<Integer, Plan.Job>> ordered = new ArrayList<>();
        for (Prepared item : prepared) {
            ordered.add(Map.entry(item.position(), item.job()));
        }
        List<Candidate> pending = new ArrayList<>();
        for (int slot : longestFirst(ordered)) {
            Plan.Job job = prepared.get(slot).job();
            pending.add(new Candidate(slot, job.resources(), job.exclusive()));
        }
        Parallelism parallelism = options.parallelism();
        LinkedBlockingQueue<Map.Entry<Integer, Object>> completions = new LinkedBlockingQueue<>();
        List<Candidate> running = new ArrayList<>();
        int[] headSkips = {0};
        List<Finished> finished = new ArrayList<>();
        RuntimeException failure = null;
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                while (failure == null && !ProcessRunner.cancelled()) {
                    Integer next = nextStart(pending, running, parallelism.budget(), parallelism.workers(), headSkips);
                    if (next == null) {
                        break;
                    }
                    Candidate candidate = pending.remove((int) next);
                    running.add(candidate);
                    Prepared item = prepared.get(candidate.slot());
                    SortedMap<String, String> environment = layers.jobEnv(
                            state, item.job().key().phase(), item.job().key().project());
                    workers.execute(() -> {
                        Object result;
                        try {
                            result = Runner.executeJob(item.job(), environment, state.preflight, item.setup(), options);
                        } catch (RuntimeException | Error error) {
                            result = error;
                        }
                        completions.add(Map.entry(candidate.slot(), result));
                    });
                }
                if (running.isEmpty()) {
                    break;
                }
                Map.Entry<Integer, Object> completion;
                try {
                    completion = completions.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AnyhowError("interrupted while waiting for jobs", interrupted);
                }
                int slot = completion.getKey();
                running.removeIf(job -> job.slot() == slot);
                switch (completion.getValue()) {
                    case Runner.JobOutcome outcome ->
                        finished.add(new Finished(prepared.get(slot).position(), outcome));
                    case RuntimeException error -> failure = failure == null ? error : failure;
                    case Error error -> throw error;
                    default ->
                        throw new AnyhowError(
                                "job " + prepared.get(slot).job().key().id() + " panicked");
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return finished;
    }

    /** Queue order for parallel execution: longest estimate first, configuration order on ties. */
    static List<Integer> longestFirst(List<Map.Entry<Integer, Plan.Job>> jobs) {
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < jobs.size(); index++) {
            order.add(index);
        }
        order.sort((a, b) -> {
            int byEstimate = Long.compare(
                    jobs.get(b).getValue().estimateMs(), jobs.get(a).getValue().estimateMs());
            return byEstimate != 0
                    ? byEstimate
                    : Integer.compare(jobs.get(a).getKey(), jobs.get(b).getKey());
        });
        return order;
    }

    /**
     * Boots the job's scopes not yet started in this phase, then runs the auth preflight once per integration phase.
     * Returns the setup error, or {@code null} when setup succeeded.
     */
    static @Nullable String setupJob(Plan.Job job, RunState state, EnvLayers layers, Runner.RunOptions options) {
        ContainerSpec container = job.request().container();
        boolean ownNetwork = container != null && container.sidecars() != null;
        try {
            for (String scope : job.scopes()) {
                if (state.phaseBooted.contains(scope)) {
                    continue;
                }
                if (ownNetwork) {
                    if (!state.phaseCompose.contains(scope)) {
                        Workspace.ScopeBoot boot = Workspace.bootCompose(
                                scope,
                                state.scopes,
                                layers.phaseEnv(state, job.key().phase()));
                        boot.exports()
                                .forEach(variable -> state.serviceEnv.put(variable.getKey(), variable.getValue()));
                        state.phaseCompose.add(scope);
                    }
                } else {
                    Workspace.ScopeBoot boot = Workspace.bootScope(
                            scope,
                            state.scopes,
                            layers.phaseEnv(state, job.key().phase()));
                    boot.exports().forEach(variable -> state.serviceEnv.put(variable.getKey(), variable.getValue()));
                    state.phaseBooted.add(scope);
                }
            }
            boolean booted = !state.phaseBooted.isEmpty() || !state.phaseCompose.isEmpty();
            if (job.key().phase().equals("integration") && !state.authReady && booted) {
                List<Map.Entry<String, String>> exports = AuthPreflight.runAuthPreflight(
                        options.workspaceRoot(),
                        options.config(),
                        state.preflight,
                        options.quiet(),
                        layers.phaseEnv(state, job.key().phase()));
                exports.forEach(variable -> state.authEnv.put(variable.getKey(), variable.getValue()));
                state.authReady = true;
            }
            return null;
        } catch (RuntimeException error) {
            return AnyhowError.alternate(error);
        }
    }
}
