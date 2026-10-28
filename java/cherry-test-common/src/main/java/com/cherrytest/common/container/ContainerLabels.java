package com.cherrytest.common.container;

/** Names and labels shared by every container Cherry Test starts. */
public final class ContainerLabels {
    /** Label that carries the run id of every container Cherry Test starts. */
    public static final String RUN_LABEL = "cherry-test.run";
    /** Label that carries the {@code project/package/phase} key of the job, or {@code scope/<name>} for a scope's services. */
    public static final String JOB_LABEL = "cherry-test.job";
    /** Label that carries the process id of the Cherry Test run that owns the container. */
    public static final String PID_LABEL = "cherry-test.pid";
    /** Label that carries the {@code project/service} an image service container provides. */
    public static final String SERVICE_LABEL = "cherry-test.service";
    /** Environment variable that passes the owning run's process id to adapter processes. */
    public static final String OWNER_PID_ENV = "CHERRY_TEST_OWNER_PID";

    private ContainerLabels() {}

    /**
     * Replaces every character Docker does not accept in a container name with {@code -}, and makes sure the
     * name starts with a letter or digit.
     */
    public static String sanitizeName(String raw) {
        StringBuilder name = new StringBuilder();
        raw.codePoints()
                .forEach(c -> name.append(
                        isAsciiAlphanumeric(c) || c == '_' || c == '.' || c == '-' ? Character.toString(c) : "-"));
        if (!name.isEmpty() && isAsciiAlphanumeric(name.charAt(0))) {
            return name.toString();
        }
        return "ct" + name;
    }

    private static boolean isAsciiAlphanumeric(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
