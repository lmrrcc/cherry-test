package com.cherrytest.common.rustcompat;

import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.Locale;

public final class RustIoError {
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private RustIoError() {}

    public static String display(Throwable error) {
        return switch (error) {
            case NoSuchFileException ignored -> "No such file or directory (os error 2)";
            case AccessDeniedException ignored -> "Permission denied (os error 13)";
            case FileAlreadyExistsException ignored -> "File exists (os error 17)";
            case NotDirectoryException ignored -> "Not a directory (os error 20)";
            case DirectoryNotEmptyException ignored ->
                MAC ? "Directory not empty (os error 66)" : "Directory not empty (os error 39)";
            default -> {
                String message = error.getMessage();
                yield message == null ? error.getClass().getSimpleName() : message;
            }
        };
    }
}
