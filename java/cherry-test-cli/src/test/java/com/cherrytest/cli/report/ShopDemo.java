package com.cherrytest.cli.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class ShopDemo {
    private static final String TITLE = "<span class=\"fold-title\">";

    private ShopDemo() {}

    static TestsReport reportFromHtml(String html) {
        String body = html.substring(html.indexOf("id=\"test-list-grouped\""), html.indexOf("id=\"test-list-flat\""));
        String suiteName = "tests";
        List<Map.Entry<String, List<TestCaseView>>> casesBySuite = new ArrayList<>();
        String rest = body;
        while (true) {
            int suiteAt = rest.indexOf("<details class=\"test-suite\"");
            int caseAt = rest.indexOf("<details class=\"test-case\"");
            if (suiteAt >= 0 && caseAt >= 0 && suiteAt < caseAt) {
                String found = attribute(rest.substring(suiteAt), "data-suite");
                suiteName = found == null ? "tests" : found;
                rest = rest.substring(suiteAt + 20);
            } else if (caseAt >= 0) {
                String slice = rest.substring(caseAt);
                String tag = slice.substring(0, slice.indexOf('>'));
                String statusAttribute = attribute(tag, "data-status");
                String status = statusAttribute == null ? "ok" : statusAttribute;
                long elapsedMs = parseOrZero(attribute(tag, "data-ms"));
                String title = slice.substring(slice.indexOf(TITLE) + TITLE.length());
                title = title.split("<span", -1)[0].strip();
                int scopeAt = slice.indexOf("scope ");
                String meta = scopeAt < 0 ? "" : slice.substring(scopeAt, Math.min(slice.length(), scopeAt + 160));
                String scope = part(meta, "scope ", " ·", "none");
                String target = part(meta, "target ", "<", "shop.shop");
                String reason = status.equals("FAIL") ? whyText(slice) : null;
                TestCaseView testCase = new TestCaseView(
                        "shop", suiteName, title, title, scope, target, elapsedMs, status, "", reason, "", "",
                        List.of(), List.of(), null);
                if (!casesBySuite.isEmpty() && casesBySuite.getLast().getKey().equals(suiteName)) {
                    casesBySuite.getLast().getValue().add(testCase);
                } else {
                    List<TestCaseView> cases = new ArrayList<>();
                    cases.add(testCase);
                    casesBySuite.add(Map.entry(suiteName, cases));
                }
                rest = rest.substring(caseAt + 20);
            } else {
                break;
            }
        }
        long run = 0;
        long ok = 0;
        long fail = 0;
        long skip = 0;
        List<SuiteTestView> suites = new ArrayList<>();
        for (Map.Entry<String, List<TestCaseView>> entry : casesBySuite) {
            TestTimingStats stats = TestView.timingStats(entry.getValue().stream()
                    .map(testCase -> new TestView.StatusTiming(testCase.statusLabel(), testCase.elapsedMs()))
                    .toList());
            run += stats.run();
            ok += stats.ok();
            fail += stats.fail();
            skip += stats.skip();
            suites.add(new SuiteTestView(entry.getKey(), stats, entry.getValue()));
        }
        return new TestsReport(
                List.of(new ProjectTestView("shop", new TestTimingStats(run, ok, fail, skip, 0, 0, 0, 0), suites)),
                List.of());
    }

    private static long parseOrZero(@Nullable String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String part(String meta, String key, String terminator, String fallback) {
        String[] pieces = meta.split(key, -1);
        if (pieces.length < 2) {
            return fallback;
        }
        return pieces[1].split(Pattern.quote(terminator), -1)[0].strip();
    }

    private static @Nullable String whyText(String slice) {
        int at = slice.indexOf("class=\"why-pop\"");
        if (at < 0) {
            return null;
        }
        String after = slice.substring(at);
        int open = after.indexOf('>');
        if (open < 0) {
            return null;
        }
        String text = after.substring(open + 1).split("<", -1)[0].strip();
        return text.isEmpty() ? null : text;
    }

    static @Nullable String attribute(String tag, String name) {
        String key = name + "=\"";
        int start = tag.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = tag.indexOf('"', start);
        return end < 0 ? null : tag.substring(start, end);
    }

    static String stripAnsi(String value) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            char character = value.charAt(index);
            if (character == '\u001b') {
                while (index < value.length() && value.charAt(index) != 'm') {
                    index++;
                }
                index++;
                continue;
            }
            out.append(character);
            index++;
        }
        return out.toString();
    }
}
