package com.cherrytest.common.rustcompat;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class AnyhowError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AnyhowError(String message) {
        super(message, null, false, false);
    }

    public AnyhowError(String message, @Nullable Throwable cause) {
        super(message, cause, false, false);
    }

    public static AnyhowError context(String message, Throwable cause) {
        return new AnyhowError(message, cause);
    }

    public static String alternate(Throwable error) {
        return String.join(": ", chain(error));
    }

    public static String debug(Throwable error) {
        List<String> messages = chain(error);
        StringBuilder rendered = new StringBuilder(messages.getFirst());
        if (messages.size() == 1) {
            return rendered.toString();
        }
        rendered.append("\n\nCaused by:");
        if (messages.size() == 2) {
            rendered.append("\n    ").append(messages.get(1));
            return rendered.toString();
        }
        for (int index = 1; index < messages.size(); index++) {
            rendered.append("\n    ").append(index - 1).append(": ").append(messages.get(index));
        }
        return rendered.toString();
    }

    public static List<String> chain(Throwable error) {
        List<String> messages = new ArrayList<>();
        Throwable current = error;
        while (current != null) {
            messages.add(display(current));
            current = current.getCause();
        }
        return messages;
    }

    public static String display(Throwable error) {
        if (error instanceof AnyhowError) {
            String message = error.getMessage();
            return message == null ? "" : message;
        }
        return RustIoError.display(error);
    }
}
