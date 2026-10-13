package com.cherrytest.parity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class Placeholders {
    static final String RUN_ID = "@CHERRY_PARITY_RUN_ID@";
    static final String SHIM_DIR = "@CHERRY_PARITY_SHIM_DIR@";
    static final Pattern ENDPOINT_VARIABLE = Pattern.compile("CHERRY_TEST_[A-Z0-9_]+_(HOST|PORT|URL)");

    private Placeholders() {}

    static String normalize(String value, String runId) {
        return runId.isEmpty() ? value : value.replace(runId, RUN_ID);
    }

    static String expand(String value, String runId) {
        return runId.isEmpty() ? value : value.replace(RUN_ID, runId);
    }

    static byte[] normalizeBytes(byte[] content, String runId) {
        if (runId.isEmpty()) {
            return content;
        }
        return replace(content, runId.getBytes(StandardCharsets.UTF_8), RUN_ID.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] expandBytes(byte[] content, String runId) {
        if (runId.isEmpty()) {
            return content;
        }
        return replace(content, RUN_ID.getBytes(StandardCharsets.UTF_8), runId.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] replace(byte[] content, byte[] target, byte[] replacement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        int index = 0;
        while (index < content.length) {
            if (matches(content, index, target)) {
                out.write(replacement, 0, replacement.length);
                index += target.length;
            } else {
                out.write(content[index]);
                index++;
            }
        }
        return out.toByteArray();
    }

    private static boolean matches(byte[] content, int index, byte[] target) {
        if (index + target.length > content.length) {
            return false;
        }
        for (int offset = 0; offset < target.length; offset++) {
            if (content[index + offset] != target[offset]) {
                return false;
            }
        }
        return true;
    }

    static boolean argvMatches(String tool, List<String> recorded, List<String> actual) {
        if (recorded.equals(actual)) {
            return true;
        }
        if (!tool.equals("docker") || recorded.size() != actual.size()) {
            return false;
        }
        int up = recorded.indexOf("--wait");
        if (up < 0 || actual.indexOf("--wait") != up) {
            return false;
        }
        if (!recorded.subList(0, up + 1).equals(actual.subList(0, up + 1))) {
            return false;
        }
        List<String> recordedServices = new ArrayList<>(recorded.subList(up + 1, recorded.size()));
        List<String> actualServices = new ArrayList<>(actual.subList(up + 1, actual.size()));
        recordedServices.sort(String::compareTo);
        actualServices.sort(String::compareTo);
        return recordedServices.equals(actualServices);
    }

    static boolean isComposePort(String tool, List<String> argv) {
        int compose = argv.indexOf("compose");
        return tool.equals("docker") && compose == 0 && argv.contains("port");
    }
}
