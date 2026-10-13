package com.cherrytest.common.process;

import java.util.Arrays;

public final class CommandOutput {
    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;

    public CommandOutput(int exitCode, byte[] stdout, byte[] stderr) {
        this.exitCode = exitCode;
        this.stdout = Arrays.copyOf(stdout, stdout.length);
        this.stderr = Arrays.copyOf(stderr, stderr.length);
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean success() {
        return exitCode == 0;
    }

    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }

    public String stdoutUtf8Strict() {
        return Utf8.decodeStrict(stdout);
    }

    public String stdoutLossy() {
        return Utf8.decodeLossy(stdout);
    }

    public String stderrLossy() {
        return Utf8.decodeLossy(stderr);
    }
}
