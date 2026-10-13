package com.cherrytest.parity;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotCaret;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

public final class Pixels {
    private static final PrintStream OUT = System.out;
    private static final int ANCHOR_SAMPLE = 12;
    private static final int TILE_HEIGHT = 4000;

    private Pixels() {}

    record Viewport(String name, int width, int height) {}

    record State(String name, String fragment, String script) {}

    public static void main(String[] arguments) throws IOException {
        Path expected = Path.of(arguments[0]);
        Path actual = Path.of(arguments[1]);
        Path output = Path.of(arguments[2]);
        Path chromium = Path.of(arguments[3]);
        Files.createDirectories(output);
        List<Viewport> viewports = List.of(new Viewport("desktop", 1440, 900), new Viewport("phone", 390, 844));
        int compared = 0;
        List<String> mismatches = new ArrayList<>();
        try (Playwright playwright = Playwright.create(
                        new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                Browser browser =
                        playwright.chromium().launch(new BrowserType.LaunchOptions().setExecutablePath(chromium))) {
            for (String document : List.of("index.html", "phases.html")) {
                for (State state : states(expected.resolve(document))) {
                    for (Viewport viewport : viewports) {
                        for (boolean dark : List.of(false, true)) {
                            String label = document.replace(".html", "") + "-" + state.name() + "-" + viewport.name()
                                    + (dark ? "-dark" : "-light");
                            List<byte[]> left = screenshot(browser, expected.resolve(document), state, viewport, dark);
                            List<byte[]> right = screenshot(browser, actual.resolve(document), state, viewport, dark);
                            compared++;
                            String difference = difference(left, right, output, label);
                            if (!difference.isEmpty()) {
                                mismatches.add(label + ": " + difference);
                            }
                        }
                    }
                }
            }
        }
        mismatches.forEach(OUT::println);
        OUT.println(
                mismatches.isEmpty()
                        ? "IDENTICAL pixels (" + compared + " full-page screenshots, zero tolerance)"
                        : mismatches.size() + " of " + compared + " screenshots differ");
        System.exit(mismatches.isEmpty() ? 0 : 1);
    }

    static List<State> states(Path document) throws IOException {
        List<State> states = new ArrayList<>();
        states.add(new State("base", "", ""));
        states.add(new State("details-open", "", "document.querySelectorAll('details').forEach(d => d.open = true)"));
        String html = Files.readString(document);
        if (html.contains("id=\"test-view-toggle\"")) {
            states.add(new State("flat", "", "document.getElementById('test-view-toggle').click()"));
            for (String status : List.of("fail", "skip")) {
                states.add(new State("status-" + status, "", select("test-status", status)));
            }
            for (String sort : options(html, "test-sort")) {
                states.add(new State("sort-" + sort, "", select("test-sort", sort)));
            }
            states.add(new State(
                    "filter-text",
                    "",
                    "const f = document.getElementById('test-filter'); f.value = 'cart';"
                            + " f.dispatchEvent(new Event('input', {bubbles: true}))"));
        }
        int index = 0;
        for (String anchor : anchors(html)) {
            states.add(new State("anchor-" + index++, anchor, ""));
        }
        return states;
    }

    private static String select(String id, String value) {
        return "const s = document.getElementById('" + id + "'); s.value = '" + value + "';"
                + " s.dispatchEvent(new Event('change', {bubbles: true}))";
    }

    private static List<String> options(String html, String id) {
        int start = html.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            return List.of();
        }
        int end = html.indexOf("</select>", start);
        List<String> values = new ArrayList<>();
        int cursor = start;
        while (true) {
            int option = html.indexOf("<option value=\"", cursor);
            if (option < 0 || option > end) {
                return values;
            }
            int valueStart = option + "<option value=\"".length();
            int valueEnd = html.indexOf('"', valueStart);
            String value = html.substring(valueStart, valueEnd);
            if (!value.isEmpty() && !value.equals("default")) {
                values.add(value);
            }
            cursor = valueEnd;
        }
    }

    private static Set<String> anchors(String html) {
        Set<String> anchors = new LinkedHashSet<>();
        int cursor = 0;
        while (anchors.size() < ANCHOR_SAMPLE) {
            int id = html.indexOf(" id=\"", cursor);
            if (id < 0) {
                return anchors;
            }
            int start = id + " id=\"".length();
            int end = html.indexOf('"', start);
            String value = html.substring(start, end);
            if (anchorable(value)) {
                anchors.add(value);
            }
            cursor = end;
        }
        return anchors;
    }

    private static boolean anchorable(String value) {
        if (value.startsWith("cov-") || value.startsWith("project-") || value.startsWith("phase-")) {
            return true;
        }
        Set<String> controls = Set.of(
                "test-filter",
                "test-list-flat",
                "test-list-grouped",
                "test-view-toggle",
                "test-tag",
                "test-status",
                "test-sort");
        return value.startsWith("test-") && !controls.contains(value);
    }

    static List<byte[]> screenshot(Browser browser, Path document, State state, Viewport viewport, boolean dark) {
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(viewport.width(), viewport.height())
                .setDeviceScaleFactor(1)
                .setReducedMotion(ReducedMotion.REDUCE))) {
            Page page = context.newPage();
            String url = document.toUri().toString() + (state.fragment().isEmpty() ? "" : "#" + state.fragment());
            page.navigate(url);
            page.waitForLoadState();
            if (dark) {
                page.click("#theme-toggle");
            }
            if (!state.script().isEmpty()) {
                page.evaluate(state.script());
            }
            page.evaluate("document.fonts.ready");
            Number height = (Number) Objects.requireNonNull(page.evaluate(
                    "Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)"));
            Number width = (Number) Objects.requireNonNull(page.evaluate(
                    "Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0)"));
            List<byte[]> tiles = new ArrayList<>();
            for (int top = 0; top < height.intValue(); top += TILE_HEIGHT) {
                int tileHeight = Math.min(TILE_HEIGHT, height.intValue() - top);
                tiles.add(page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true)
                        .setClip(0, top, width.doubleValue(), tileHeight)
                        .setAnimations(ScreenshotAnimations.DISABLED)
                        .setCaret(ScreenshotCaret.HIDE)));
            }
            return tiles;
        }
    }

    static String difference(List<byte[]> left, List<byte[]> right, Path output, String label) throws IOException {
        if (left.size() != right.size()) {
            return "page height differs: " + left.size() + " vs " + right.size() + " tiles";
        }
        long differing = 0;
        List<String> details = new ArrayList<>();
        for (int index = 0; index < left.size(); index++) {
            String tile = difference(left.get(index), right.get(index), output, label + "-tile" + index);
            if (!tile.isEmpty()) {
                differing++;
                details.add("tile " + index + ": " + tile);
            }
        }
        return differing == 0 ? "" : String.join("; ", details);
    }

    static String difference(byte[] left, byte[] right, Path output, String label) throws IOException {
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(left));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(right));
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            write(left, right, null, output, label);
            return "size " + expected.getWidth() + "x" + expected.getHeight() + " vs " + actual.getWidth() + "x"
                    + actual.getHeight();
        }
        BufferedImage diff = new BufferedImage(expected.getWidth(), expected.getHeight(), BufferedImage.TYPE_INT_ARGB);
        long differing = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                boolean same = expected.getRGB(x, y) == actual.getRGB(x, y);
                diff.setRGB(x, y, same ? 0x00000000 : 0xffff00ff);
                if (!same) {
                    differing++;
                }
            }
        }
        if (differing == 0) {
            return "";
        }
        write(left, right, diff, output, label);
        return differing + " pixels differ";
    }

    private static void write(byte[] left, byte[] right, @Nullable BufferedImage diff, Path output, String label)
            throws IOException {
        Files.write(output.resolve(label + "-rust.png"), left);
        Files.write(output.resolve(label + "-java.png"), right);
        if (diff != null) {
            ImageIO.write(diff, "png", output.resolve(label + "-diff.png").toFile());
        }
    }
}
