package com.cherrytest.cli;

import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Executes a {@link Plan}: boots scopes, builds each job's environment and runs the jobs. */
public final class Scheduler {
    private Scheduler() {}

    /** What a run accumulates while it executes. The run owns it; nothing here is process-global. */
    public static final class RunState {
        final Workspace.ScopeState scopes = new Workspace.ScopeState();
        final PreflightPlan preflight;
        final SortedMap<String, String> serviceEnv = new TreeMap<>(RustOrdering.STRING_ORDER);
        final SortedMap<String, String> authEnv = new TreeMap<>(RustOrdering.STRING_ORDER);
        private final TreeSet<String> phaseBooted = new TreeSet<>(RustOrdering.STRING_ORDER);
        private boolean authReady;

        public RunState(PreflightPlan preflight) {
            this.preflight = preflight;
        }

        void startPhase() {
            phaseBooted.clear();
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

    /**
     * Executes the plan phase by phase and returns each finished job with its position in the plan. Jobs run one at
     * a time in plan order; scopes boot right before the first job that needs them and stop at the end of the phase.
     */
    public static List<Finished> run(
            Plan plan, List<ProjectGroup> projects, Runner.RunOptions options, PreflightPlan preflight) {
        EnvLayers layers = EnvLayers.load(options, projects);
        RunState state = new RunState(preflight.copy());
        List<Finished> finished = new ArrayList<>();
        int order = 0;
        for (Plan.PhasePlan phase : plan.phases()) {
            state.startPhase();
            for (Plan.Job job : phase.jobs()) {
                int position = order++;
                if (ProcessRunner.cancelled()) {
                    continue;
                }
                String setup = setupJob(job, state, layers, options);
                SortedMap<String, String> environment =
                        layers.jobEnv(state, phase.phase(), job.key().project());
                finished.add(
                        new Finished(position, Runner.executeJob(job, environment, state.preflight, setup, options)));
            }
            Workspace.teardownScopes(state.scopes, layers.phaseEnv(state, phase.phase()));
            if (ProcessRunner.cancelled()) {
                break;
            }
        }
        return finished;
    }

    /**
     * Boots the job's scopes not yet started in this phase, then runs the auth preflight once per integration phase.
     * Returns the setup error, or {@code null} when setup succeeded.
     */
    private static @Nullable String setupJob(
            Plan.Job job, RunState state, EnvLayers layers, Runner.RunOptions options) {
        try {
            for (String scope : job.scopes()) {
                if (!state.phaseBooted.contains(scope)) {
                    Workspace.ScopeBoot boot = Workspace.bootScope(
                            scope,
                            state.scopes,
                            layers.phaseEnv(state, job.key().phase()));
                    boot.exports().forEach(variable -> state.serviceEnv.put(variable.getKey(), variable.getValue()));
                    state.phaseBooted.add(scope);
                }
            }
            if (job.key().phase().equals("integration") && !state.authReady && !state.phaseBooted.isEmpty()) {
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
