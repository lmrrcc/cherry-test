package com.cherrytest.cli.nativeos;

public final class OsError {
    private OsError() {}

    public static String describe(int errno) {
        String text = switch (errno) {
            case 1 -> "Operation not permitted";
            case 2 -> "No such file or directory";
            case 7 -> "Argument list too long";
            case 8 -> "Exec format error";
            case 12 -> "Cannot allocate memory";
            case 13 -> "Permission denied";
            case 20 -> "Not a directory";
            case 21 -> "Is a directory";
            default -> "Unknown error " + errno;
        };
        return text + " (os error " + errno + ")";
    }
}
