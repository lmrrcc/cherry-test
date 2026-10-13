package com.cherrytest.cli.nativeos;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class Terminal {
    public static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    public static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private Terminal() {}

    public static void println(String line) {
        OUT.print(line);
        OUT.print('\n');
        OUT.flush();
    }

    public static void println() {
        OUT.print('\n');
        OUT.flush();
    }

    public static void print(String text) {
        OUT.print(text);
        OUT.flush();
    }

    public static void eprintln(String line) {
        ERR.print(line);
        ERR.print('\n');
        ERR.flush();
    }

    public static boolean stdoutIsTerminal() {
        return Libc.isatty(1);
    }
}
