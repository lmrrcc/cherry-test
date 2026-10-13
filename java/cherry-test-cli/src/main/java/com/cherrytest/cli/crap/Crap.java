package com.cherrytest.cli.crap;

import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.FileLineCoverage;
import com.cherrytest.cli.report.AddedLines;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class Crap {
    public static final double CRAP_THRESHOLD = 30.0;
    private static final double CRAP_HIGH = CRAP_THRESHOLD / 2.0;
    private static final double CRAP_MODERATE = 5.0;
    private static final long MAXIMUM_SOURCE_BYTES = 1_000_000;

    private Crap() {}

    enum Language {
        RUST,
        C,
        JAVA,
        KOTLIN,
        SCRIPT
    }

    private enum TokKind {
        IDENT,
        OP
    }

    private record Tok(long line, TokKind kind, String text) {}

    record FunctionSpan(String name, long start, long end, long complexity) {}

    private static final class OpenFunction {
        private final String name;
        private final long line;
        private long complexity;
        private final int depth;

        private OpenFunction(String name, long line, int depth) {
            this.name = name;
            this.line = line;
            this.complexity = 1;
            this.depth = depth;
        }
    }

    private record Pending(String name, long line) {}

    public static CrapSeverity crapSeverity(double score) {
        if (score >= CRAP_THRESHOLD) {
            return CrapSeverity.CRITICAL;
        }
        if (score >= CRAP_HIGH) {
            return CrapSeverity.HIGH;
        }
        if (score >= CRAP_MODERATE) {
            return CrapSeverity.MODERATE;
        }
        return CrapSeverity.LOW;
    }

    public static List<CrapSourceLine> functionSource(
            String source, long start, long end, Map<Long, Boolean> coverage) {
        List<CrapSourceLine> out = new ArrayList<>();
        long number = 0;
        for (String text : RustText.lines(source)) {
            number = (number + 1) & 0xFFFFFFFFL;
            if (number < start) {
                continue;
            }
            if (number > end) {
                break;
            }
            Boolean hit = coverage.get(number);
            LineMeasure measure =
                    hit == null ? LineMeasure.NOT_MEASURED : hit ? LineMeasure.COVERED : LineMeasure.UNCOVERED;
            out.add(new CrapSourceLine(number, text, measure));
        }
        return out;
    }

    public static double crapScore(long complexity, double coverage) {
        double value = (double) Math.max(complexity, 1);
        double gap = clamp(1.0 - coverage);
        return value * value * gap * gap * gap + value;
    }

    public static double crapLoad(long complexity, double coverage) {
        if (crapScore(complexity, coverage) < CRAP_THRESHOLD) {
            return 0.0;
        }
        double value = (double) Math.max(complexity, 1);
        double gap = clamp(1.0 - coverage);
        return value * gap + value / CRAP_THRESHOLD;
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    public static CrapTotals crapTotals(List<CrapFunction> functions) {
        int count = functions.size();
        if (count == 0) {
            return CrapTotals.ZERO;
        }
        List<Double> scores = new ArrayList<>();
        functions.forEach(function -> scores.add(function.score()));
        scores.sort(Double::compare);
        int middle = count / 2;
        double median = count % 2 == 0 ? (scores.get(middle - 1) + scores.get(middle)) / 2.0 : scores.get(middle);
        long complexity = 0;
        double total = 0.0;
        double load = 0.0;
        long crappy = 0;
        for (CrapFunction function : functions) {
            complexity += function.complexity();
            total += function.score();
            load += function.crapLoad();
            if (function.score() >= CRAP_THRESHOLD) {
                crappy++;
            }
        }
        return new CrapTotals(
                count,
                complexity,
                total,
                total / (double) count,
                median,
                scores.get(count - 1),
                crappy,
                (double) crappy / (double) count * 100.0,
                load);
    }

    public static @Nullable CrapChange changeSlice(List<CrapFunction> functions, List<String> changed) {
        if (changed.isEmpty()) {
            return null;
        }
        List<CrapFunction> matched = functions.stream()
                .filter(function -> Coverage.pathMatchesChanged(function.path(), changed))
                .toList();
        return new CrapChange(matched, crapTotals(matched));
    }

    public static @Nullable CrapChange changeOnAddedLines(List<CrapFunction> functions, List<AddedLines> files) {
        if (files.isEmpty()) {
            return null;
        }
        List<CrapFunction> matched = functions.stream()
                .filter(function -> files.stream()
                        .anyMatch(file -> Coverage.pathMatchesChanged(function.path(), List.of(file.path()))
                                && functionTouchesLines(function, file.lines())))
                .toList();
        return new CrapChange(matched, crapTotals(matched));
    }

    private static boolean functionTouchesLines(CrapFunction function, SortedSet<Long> lines) {
        if (lines.contains(function.line())) {
            return true;
        }
        return function.source().stream().anyMatch(line -> lines.contains(line.number()));
    }

    public static List<CrapProjectTotals> projectRollups(
            Path workspace, List<CrapFunction> functions, List<CrapProjectRoot> projects) {
        if (projects.isEmpty() || functions.isEmpty()) {
            return List.of();
        }
        TreeMap<String, List<CrapFunction>> grouped = new TreeMap<>(RustOrdering.STRING_ORDER);
        List<CrapFunction> outside = new ArrayList<>();
        for (CrapFunction function : functions) {
            RustLexicalPath file = filePath(workspace, function.path());
            List<String> best = fileProject(file, projects);
            if (best.size() > 1) {
                return List.of();
            }
            if (best.isEmpty()) {
                outside.add(function);
            } else {
                grouped.computeIfAbsent(best.getFirst(), key -> new ArrayList<>())
                        .add(function);
            }
        }
        List<CrapProjectTotals> rollups = new ArrayList<>();
        grouped.forEach((project, group) -> rollups.add(new CrapProjectTotals(project, crapTotals(group))));
        if (!outside.isEmpty()) {
            rollups.add(new CrapProjectTotals("outside projects", crapTotals(outside)));
        }
        return rollups;
    }

    public static CrapReport scoreReport(Path workspace, List<FileLineCoverage> files, List<CrapProjectRoot> projects) {
        List<CrapFunction> functions = scoreFiles(workspace, files);
        return new CrapReport(
                true, functions, crapTotals(functions), projectRollups(workspace, functions, projects), null);
    }

    private static List<String> fileProject(RustLexicalPath file, List<CrapProjectRoot> projects) {
        int bestLength = 0;
        List<String> best = new ArrayList<>();
        for (CrapProjectRoot project : projects) {
            RustLexicalPath root = RustLexicalPath.of(project.path().toString()).normalized();
            if (root.isEmpty()) {
                continue;
            }
            if (file.startsWith(root)) {
                int length = root.componentCount();
                if (length > bestLength) {
                    bestLength = length;
                    best.clear();
                    best.add(project.id());
                } else if (length == bestLength) {
                    best.add(project.id());
                }
            }
        }
        return best;
    }

    private static RustLexicalPath filePath(Path workspace, String path) {
        String raw = path.replace('\\', '/');
        String joined = raw.startsWith("/") ? raw : RustLexicalPath.join(workspace.toString(), raw);
        return RustLexicalPath.of(joined).normalized();
    }

    public static List<CrapFunction> scoreFiles(Path workspace, List<FileLineCoverage> files) {
        TreeMap<String, SortedMap<Long, Boolean>> merged = new TreeMap<>(RustOrdering.STRING_ORDER);
        TreeMap<String, String> display = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (FileLineCoverage file : files) {
            if (skippedPath(file.path())) {
                continue;
            }
            String key = file.path().replace('\\', '/');
            display.putIfAbsent(key, file.path());
            SortedMap<Long, Boolean> entry = merged.getOrDefault(key, new TreeMap<>());
            merged.put(key, Coverage.mergeLineCoverageMaps(entry, file.lines()));
        }
        List<CrapFunction> scored = new ArrayList<>();
        for (Map.Entry<String, SortedMap<Long, Boolean>> entry : merged.entrySet()) {
            String key = entry.getKey();
            SortedMap<Long, Boolean> lines = entry.getValue();
            Language language = languageFor(key);
            if (language == null) {
                continue;
            }
            String displayPath = display.getOrDefault(key, key);
            Path path = sourceFile(workspace, displayPath);
            if (path == null) {
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException exception) {
                continue;
            }
            String source = decodeStrict(bytes);
            if (source == null) {
                continue;
            }
            if (bytes.length > MAXIMUM_SOURCE_BYTES) {
                continue;
            }
            boolean vue = language == Language.SCRIPT && key.endsWith(".vue");
            String prepared = vue ? vueScript(source) : source;
            for (FunctionSpan function : functionsIn(prepared, language)) {
                long measured = 0;
                long covered = 0;
                for (Map.Entry<Long, Boolean> line : lines.entrySet()) {
                    if (line.getKey() >= function.start() && line.getKey() <= function.end()) {
                        measured++;
                        if (line.getValue()) {
                            covered++;
                        }
                    }
                }
                double coverage = measured == 0 ? 0.0 : clamp((double) covered / (double) measured);
                scored.add(new CrapFunction(
                        displayPath,
                        function.name(),
                        function.start(),
                        function.complexity(),
                        covered,
                        measured,
                        coverage,
                        crapScore(function.complexity(), coverage),
                        crapLoad(function.complexity(), coverage),
                        functionSource(source, function.start(), function.end(), lines)));
            }
        }
        scored.sort(Comparator.<CrapFunction>comparingDouble(CrapFunction::score)
                .reversed()
                .thenComparing(CrapFunction::path, RustOrdering.STRING_ORDER)
                .thenComparingLong(CrapFunction::line)
                .thenComparing(CrapFunction::name, RustOrdering.STRING_ORDER));
        return scored;
    }

    private static @Nullable String decodeStrict(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static @Nullable Path sourceFile(Path workspace, String lcovPath) {
        Path raw = Path.of(lcovPath);
        Path candidate = raw.isAbsolute() ? raw : workspace.resolve(raw);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static boolean skippedPath(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.endsWith("_test.rs")
                || name.endsWith("_tests.rs")
                || name.contains(".test.")
                || name.contains(".spec.")
                || name.endsWith("Test.java")
                || name.endsWith("Tests.java")
                || name.endsWith("Test.kt")
                || name.endsWith("Tests.kt")) {
            return true;
        }
        for (String part : normalized.split("/", -1)) {
            switch (part) {
                case "test", "tests", "__tests__", "node_modules", "target", "build" -> {
                    return true;
                }
                default -> {}
            }
        }
        return false;
    }

    static @Nullable Language languageFor(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(separator + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "rs" -> Language.RUST;
            case "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> Language.C;
            case "java" -> Language.JAVA;
            case "kt", "kts" -> Language.KOTLIN;
            case "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue" -> Language.SCRIPT;
            default -> null;
        };
    }

    static String vueScript(String source) {
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (String line : RustText.lines(source)) {
            String trimmed = RustText.trimStart(line);
            if (!inside && trimmed.startsWith("<script")) {
                inside = true;
                out.append('\n');
                continue;
            }
            if (inside && trimmed.startsWith("</script")) {
                inside = false;
                out.append('\n');
                continue;
            }
            if (inside) {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    static List<FunctionSpan> functionsIn(String source, Language language) {
        List<Tok> tokens = tokenize(source);
        List<FunctionSpan> functions = new ArrayList<>();
        List<OpenFunction> stack = new ArrayList<>();
        List<Integer> arms = new ArrayList<>();
        int brace = 0;
        int paren = 0;
        Pending pending = null;
        boolean armPending = false;
        for (int index = 0; index < tokens.size(); index++) {
            Tok token = tokens.get(index);
            if (token.kind() == TokKind.IDENT) {
                if (isDecision(token.text()) && !stack.isEmpty()) {
                    stack.getLast().complexity++;
                }
                if (token.text().equals("fn")
                        || token.text().equals("fun")
                        || token.text().equals("function")) {
                    pending = nextName(tokens, index, token.text());
                }
                if (token.text().equals("match")
                        || token.text().equals("when")
                        || token.text().equals("switch")) {
                    armPending = true;
                }
                continue;
            }
            switch (token.text()) {
                case "{" -> {
                    brace++;
                    if (armPending) {
                        arms.add(brace);
                        armPending = false;
                    }
                    if (pending == null
                            && (language == Language.C || language == Language.JAVA || language == Language.SCRIPT)) {
                        pending = signatureName(tokens, index);
                    }
                    if (pending != null) {
                        stack.add(new OpenFunction(pending.name(), pending.line(), brace));
                        pending = null;
                    }
                }
                case "}" -> {
                    brace--;
                    while (!arms.isEmpty() && arms.getLast() > brace) {
                        arms.removeLast();
                    }
                    while (!stack.isEmpty() && stack.getLast().depth > brace) {
                        OpenFunction function = stack.removeLast();
                        functions.add(
                                new FunctionSpan(function.name, function.line, token.line(), function.complexity));
                    }
                }
                case "(" -> paren++;
                case ")" -> paren--;
                case ";" -> {
                    if (paren == 0) {
                        pending = null;
                        armPending = false;
                    }
                }
                case "=>" -> {
                    if (!arms.isEmpty() && arms.getLast() == brace && !stack.isEmpty()) {
                        stack.getLast().complexity++;
                    }
                    if (language == Language.SCRIPT) {
                        pending = arrowName(tokens, index);
                    }
                }
                case "->" -> {
                    if (!arms.isEmpty() && arms.getLast() == brace && !stack.isEmpty()) {
                        stack.getLast().complexity++;
                    }
                }
                case "&&", "||" -> {
                    if (!stack.isEmpty()) {
                        stack.getLast().complexity++;
                    }
                }
                case "?" -> {
                    if (language != Language.RUST && !stack.isEmpty()) {
                        stack.getLast().complexity++;
                    }
                }
                default -> {}
            }
        }
        return functions;
    }

    private static boolean isDecision(String text) {
        return switch (text) {
            case "if", "while", "for", "loop", "catch", "case", "elif", "elsif" -> true;
            default -> false;
        };
    }

    private static Pending nextName(List<Tok> tokens, int index, String keyword) {
        String fallback = keyword.equals("function") ? "<function>" : "<fn>";
        if (index + 1 < tokens.size()) {
            Tok token = tokens.get(index + 1);
            if (token.kind() == TokKind.IDENT && !isReserved(token.text())) {
                return new Pending(token.text(), token.line());
            }
            return new Pending(fallback, token.line());
        }
        return new Pending(fallback, tokens.get(index).line());
    }

    private static Pending arrowName(List<Tok> tokens, int index) {
        int cursor = index;
        if (index > 0 && tokens.get(index - 1).text().equals(")")) {
            int depth = 0;
            while (cursor > 0) {
                cursor--;
                if (tokens.get(cursor).text().equals(")")) {
                    depth++;
                } else if (tokens.get(cursor).text().equals("(")) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
        }
        while (cursor > 0) {
            cursor--;
            Tok token = tokens.get(cursor);
            if (token.kind() == TokKind.IDENT && !isReserved(token.text())) {
                return new Pending(token.text(), token.line());
            }
            if (token.text().equals(";")
                    || token.text().equals("{")
                    || token.text().equals("}")) {
                break;
            }
        }
        return new Pending("<arrow>", tokens.get(index).line());
    }

    private static @Nullable Pending signatureName(List<Tok> tokens, int brace) {
        int cursor = brace;
        boolean sawParen = false;
        while (cursor > 0) {
            cursor--;
            Tok token = tokens.get(cursor);
            if (token.text().equals(")")) {
                sawParen = true;
                break;
            }
            if (token.text().equals(";")
                    || token.text().equals("{")
                    || token.text().equals("}")
                    || token.text().equals("=>")) {
                return null;
            }
            if (token.kind() == TokKind.IDENT
                    || token.text().equals(".")
                    || token.text().equals(",")) {
                continue;
            }
            return null;
        }
        if (!sawParen) {
            return null;
        }
        int depth = 0;
        while (true) {
            if (tokens.get(cursor).text().equals(")")) {
                depth++;
            } else if (tokens.get(cursor).text().equals("(")) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            if (cursor == 0) {
                return null;
            }
            cursor--;
        }
        while (cursor > 0) {
            cursor--;
            Tok token = tokens.get(cursor);
            if (token.text().equals("*")
                    || token.text().equals("&")
                    || token.text().equals(".")) {
                continue;
            }
            if (token.kind() == TokKind.IDENT && !isReserved(token.text())) {
                return new Pending(token.text(), token.line());
            }
            return null;
        }
        return null;
    }

    private static boolean isReserved(String text) {
        return switch (text) {
            case "if",
                    "while",
                    "for",
                    "switch",
                    "catch",
                    "else",
                    "do",
                    "try",
                    "foreach",
                    "synchronized",
                    "function",
                    "fn",
                    "fun",
                    "match",
                    "when",
                    "loop",
                    "return",
                    "case",
                    "default",
                    "new",
                    "throws" -> true;
            default -> false;
        };
    }

    private static final String[] OPERATORS = {
        "&&=", "||=", "&&", "||", "=>", "->", "?.", "??", "==", "!=", "<=", ">=", "::"
    };

    private static List<Tok> tokenize(String source) {
        int[] chars = source.codePoints().toArray();
        List<Tok> tokens = new ArrayList<>();
        int index = 0;
        long line = 1;
        while (index < chars.length) {
            int character = chars[index];
            if (character == '\n') {
                line = (line + 1) & 0xFFFFFFFFL;
                index++;
                continue;
            }
            if (RustText.isWhitespace(character)) {
                index++;
                continue;
            }
            if (character == '/' && at(chars, index + 1) == '/') {
                index += 2;
                while (index < chars.length && chars[index] != '\n') {
                    index++;
                }
                continue;
            }
            if (character == '/' && at(chars, index + 1) == '*') {
                index += 2;
                while (index + 1 < chars.length && !(chars[index] == '*' && chars[index + 1] == '/')) {
                    if (chars[index] == '\n') {
                        line = (line + 1) & 0xFFFFFFFFL;
                    }
                    index++;
                }
                index = Math.min(index + 2, chars.length);
                continue;
            }
            if (character == '`') {
                index++;
                while (index < chars.length && chars[index] != '`') {
                    if (chars[index] == '\n') {
                        line = (line + 1) & 0xFFFFFFFFL;
                    }
                    if (chars[index] == '\\') {
                        index++;
                    }
                    index++;
                }
                index = Math.min(index + 1, chars.length);
                continue;
            }
            if (character == '"' || (character == 'r' && rawStringAt(chars, index) >= 0)) {
                int end = rawStringAt(chars, index);
                if (end >= 0) {
                    for (int cursor = index; cursor < end; cursor++) {
                        if (chars[cursor] == '\n') {
                            line = (line + 1) & 0xFFFFFFFFL;
                        }
                    }
                    index = end;
                    continue;
                }
                index++;
                while (index < chars.length && chars[index] != '"') {
                    if (chars[index] == '\n') {
                        line = (line + 1) & 0xFFFFFFFFL;
                    }
                    if (chars[index] == '\\') {
                        index++;
                    }
                    index++;
                }
                index = Math.min(index + 1, chars.length);
                continue;
            }
            if (character == '\'') {
                int end = charLiteralEnd(chars, index);
                index = end >= 0 ? end : index + 1;
                continue;
            }
            if (character == '_' || isAsciiAlphabetic(character)) {
                int start = index;
                index++;
                while (index < chars.length && (chars[index] == '_' || isAsciiAlphanumeric(chars[index]))) {
                    index++;
                }
                tokens.add(new Tok(line, TokKind.IDENT, new String(chars, start, index - start)));
                continue;
            }
            String operator = matchOperator(chars, index);
            if (operator != null) {
                tokens.add(new Tok(line, TokKind.OP, operator));
                index += operator.codePointCount(0, operator.length());
                continue;
            }
            tokens.add(new Tok(line, TokKind.OP, Character.toString(character)));
            index++;
        }
        return tokens;
    }

    private static @Nullable String matchOperator(int[] chars, int index) {
        for (String operator : OPERATORS) {
            if (startsWith(chars, index, operator)) {
                return operator;
            }
        }
        return null;
    }

    private static boolean startsWith(int[] chars, int index, String text) {
        int[] expected = text.codePoints().toArray();
        if (chars.length - index < expected.length) {
            return false;
        }
        for (int offset = 0; offset < expected.length; offset++) {
            if (chars[index + offset] != expected[offset]) {
                return false;
            }
        }
        return true;
    }

    private static int at(int[] chars, int index) {
        return index < chars.length ? chars[index] : -1;
    }

    private static boolean isAsciiAlphabetic(int character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    private static boolean isAsciiAlphanumeric(int character) {
        return isAsciiAlphabetic(character) || (character >= '0' && character <= '9');
    }

    private static int rawStringAt(int[] chars, int index) {
        if (at(chars, index) != 'r') {
            return -1;
        }
        int cursor = index + 1;
        int hashes = 0;
        while (at(chars, cursor) == '#') {
            hashes++;
            cursor++;
        }
        if (at(chars, cursor) != '"') {
            return -1;
        }
        cursor++;
        while (cursor < chars.length) {
            if (chars[cursor] == '"') {
                int matched = 0;
                while (matched < hashes && at(chars, cursor + 1 + matched) == '#') {
                    matched++;
                }
                if (matched == hashes) {
                    return cursor + 1 + hashes;
                }
            }
            cursor++;
        }
        return chars.length;
    }

    private static int charLiteralEnd(int[] chars, int index) {
        if (index + 1 >= chars.length) {
            return -1;
        }
        int next = chars[index + 1];
        if (next == '\\') {
            if (index + 3 >= chars.length) {
                return -1;
            }
            return chars[index + 3] == '\'' ? index + 4 : -1;
        }
        if (next != '\'' && at(chars, index + 2) == '\'') {
            return index + 3;
        }
        return -1;
    }
}
