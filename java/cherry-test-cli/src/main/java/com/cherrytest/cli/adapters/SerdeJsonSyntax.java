package com.cherrytest.cli.adapters;

import org.jspecify.annotations.Nullable;

final class SerdeJsonSyntax {
    private final byte[] input;
    private int index;

    private SerdeJsonSyntax(byte[] input) {
        this.input = input;
    }

    static @Nullable String firstError(byte[] input) {
        SerdeJsonSyntax parser = new SerdeJsonSyntax(input);
        try {
            parser.parseValue();
            parser.skipWhitespace();
            if (parser.index < parser.input.length) {
                return parser.errorAt("trailing characters", parser.peekPosition());
            }
            return null;
        } catch (Failure failure) {
            return failure.getMessage();
        }
    }

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Failure(String message) {
            super(message, null, false, false);
        }
    }

    private void parseValue() {
        skipWhitespace();
        if (index >= input.length) {
            throw fail("EOF while parsing a value", peekPosition());
        }
        byte next = input[index];
        switch (next) {
            case 'n' -> {
                index++;
                ident("ull");
            }
            case 't' -> {
                index++;
                ident("rue");
            }
            case 'f' -> {
                index++;
                ident("alse");
            }
            case '"' -> {
                index++;
                string();
            }
            case '[' -> {
                index++;
                sequence();
            }
            case '{' -> {
                index++;
                map();
            }
            default -> {
                if (next == '-' || (next >= '0' && next <= '9')) {
                    number();
                } else {
                    throw fail("expected value", peekPosition());
                }
            }
        }
    }

    private void ident(String rest) {
        for (int position = 0; position < rest.length(); position++) {
            if (index >= input.length) {
                throw fail("EOF while parsing a value", currentPosition());
            }
            byte character = input[index++];
            if (character != rest.charAt(position)) {
                throw fail("expected ident", currentPosition());
            }
        }
    }

    private void string() {
        while (true) {
            if (index >= input.length) {
                throw fail("EOF while parsing a string", currentPosition());
            }
            byte character = input[index++];
            if (character == '"') {
                return;
            }
            if (character == '\\') {
                if (index >= input.length) {
                    throw fail("EOF while parsing a string", currentPosition());
                }
                byte escape = input[index++];
                switch (escape) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}
                    case 'u' -> {
                        for (int digit = 0; digit < 4; digit++) {
                            if (index >= input.length) {
                                throw fail("EOF while parsing a string", currentPosition());
                            }
                            byte hex = input[index++];
                            if (Character.digit(hex, 16) < 0) {
                                throw fail("invalid escape", currentPosition());
                            }
                        }
                    }
                    default -> throw fail("invalid escape", currentPosition());
                }
            } else if ((character & 0xff) < 0x20) {
                throw fail("control character (\\u0000-\\u001F) found while parsing a string", currentPosition());
            }
        }
    }

    private void number() {
        if (input[index] == '-') {
            index++;
        }
        if (index >= input.length) {
            throw fail("EOF while parsing a value", currentPosition());
        }
        byte first = input[index++];
        if (first == '0') {
            if (index < input.length && input[index] >= '0' && input[index] <= '9') {
                throw fail("invalid number", peekPosition());
            }
        } else if (first < '1' || first > '9') {
            throw fail("invalid number", currentPosition());
        }
        while (index < input.length && input[index] >= '0' && input[index] <= '9') {
            index++;
        }
        if (index < input.length && input[index] == '.') {
            index++;
            if (index >= input.length) {
                throw fail("EOF while parsing a value", currentPosition());
            }
            if (input[index] < '0' || input[index] > '9') {
                throw fail("invalid number", peekPosition());
            }
            while (index < input.length && input[index] >= '0' && input[index] <= '9') {
                index++;
            }
        }
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++;
            if (index < input.length && (input[index] == '+' || input[index] == '-')) {
                index++;
            }
            if (index >= input.length) {
                throw fail("EOF while parsing a value", currentPosition());
            }
            if (input[index] < '0' || input[index] > '9') {
                throw fail("invalid number", peekPosition());
            }
            while (index < input.length && input[index] >= '0' && input[index] <= '9') {
                index++;
            }
        }
    }

    private void sequence() {
        skipWhitespace();
        if (index < input.length && input[index] == ']') {
            index++;
            return;
        }
        while (true) {
            parseValue();
            skipWhitespace();
            if (index >= input.length) {
                throw fail("EOF while parsing a list", peekPosition());
            }
            byte next = input[index];
            if (next == ']') {
                index++;
                return;
            }
            if (next != ',') {
                throw fail("expected `,` or `]`", peekPosition());
            }
            index++;
            skipWhitespace();
            if (index < input.length && input[index] == ']') {
                throw fail("trailing comma", peekPosition());
            }
        }
    }

    private void map() {
        skipWhitespace();
        if (index < input.length && input[index] == '}') {
            index++;
            return;
        }
        while (true) {
            skipWhitespace();
            if (index >= input.length) {
                throw fail("EOF while parsing an object", peekPosition());
            }
            if (input[index] != '"') {
                throw fail("key must be a string", peekPosition());
            }
            index++;
            string();
            skipWhitespace();
            if (index >= input.length) {
                throw fail("EOF while parsing an object", peekPosition());
            }
            if (input[index] != ':') {
                throw fail("expected `:`", peekPosition());
            }
            index++;
            parseValue();
            skipWhitespace();
            if (index >= input.length) {
                throw fail("EOF while parsing an object", peekPosition());
            }
            byte next = input[index];
            if (next == '}') {
                index++;
                return;
            }
            if (next != ',') {
                throw fail("expected `,` or `}`", peekPosition());
            }
            index++;
            skipWhitespace();
            if (index < input.length && input[index] == '}') {
                throw fail("trailing comma", peekPosition());
            }
        }
    }

    private void skipWhitespace() {
        while (index < input.length) {
            byte character = input[index];
            if (character == ' ' || character == '\n' || character == '\t' || character == '\r') {
                index++;
            } else {
                return;
            }
        }
    }

    private long[] currentPosition() {
        return positionOf(index);
    }

    private long[] peekPosition() {
        return positionOf(Math.min(index + 1, input.length));
    }

    private long[] positionOf(int end) {
        long line = 1;
        long column = 0;
        for (int position = 0; position < end; position++) {
            if (input[position] == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new long[] {line, column};
    }

    private Failure fail(String reason, long[] position) {
        return new Failure(errorAt(reason, position));
    }

    private String errorAt(String reason, long[] position) {
        return reason + " at line " + position[0] + " column " + position[1];
    }
}
