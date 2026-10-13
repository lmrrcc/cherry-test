package com.cherrytest.cli.nativeos;

public record ExitStatus(boolean running, boolean success, int rawStatus) {
    public static final ExitStatus RUNNING = new ExitStatus(true, false, 0);

    public static ExitStatus of(int rawStatus) {
        boolean exited = (rawStatus & 0x7f) == 0;
        int code = (rawStatus >> 8) & 0xff;
        return new ExitStatus(false, rawStatus >= 0 && exited && code == 0, rawStatus);
    }
}
