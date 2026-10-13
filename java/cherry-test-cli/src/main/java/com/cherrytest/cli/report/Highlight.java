package com.cherrytest.cli.report;

import com.cherrytest.common.rustcompat.RustText;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class Highlight {
    private Highlight() {}

    private enum Kind {
        PLAIN,
        COMMENT,
        STRING,
        NUMBER,
        ATTRIBUTE,
        KEYWORD,
        MACRO,
        CALL,
        TYPE,
        IDENT
    }

    private record Token(Kind kind, String text) {}

    private record Taken(Token token, int next) {}

    public static HighlightLang highlightLangForPath(String path) {
        String lower = RustText.toAsciiLowercase(path);
        if (lower.endsWith(".rs")) {
            return HighlightLang.RUST;
        }
        if (lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".mjs")
                || lower.endsWith(".cjs")) {
            return HighlightLang.TYPESCRIPT;
        }
        if (lower.endsWith(".json")
                || lower.endsWith(".yaml")
                || lower.endsWith(".yml")
                || lower.endsWith(".toml")
                || lower.endsWith(".lock")) {
            return HighlightLang.CONFIG;
        }
        return HighlightLang.PLAIN;
    }

    public static HighlightLang snippetLanguage(String source) {
        long rust = signalCount(source, List.of("use ", "pub ", "impl ", "fn ", " fn ", "mod "));
        long script = signalCount(source, List.of("import ", "export ", "function ", "interface ", "const ", "=>"));
        if (rust > 0 && rust >= script) {
            return HighlightLang.RUST;
        }
        if (script > 0) {
            return HighlightLang.TYPESCRIPT;
        }
        return HighlightLang.PLAIN;
    }

    public static String highlightLine(HighlightLang lang, String text) {
        return highlightLinked(lang, text, name -> null);
    }

    public static String highlightLinked(HighlightLang lang, String text, Function<String, @Nullable String> hrefFor) {
        StringBuilder html = new StringBuilder();
        for (Token token : scan(lang, text)) {
            html.append(renderToken(lang, token, hrefFor));
        }
        return html.toString();
    }

    static List<String> definedNames(HighlightLang lang, String line) {
        if (lang != HighlightLang.RUST && lang != HighlightLang.TYPESCRIPT) {
            return List.of();
        }
        List<Token> tokens = scan(lang, line);
        List<String> names = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.kind() == Kind.KEYWORD && isDefKeyword(lang, token.text())) {
                int next = index + 1;
                while (next < tokens.size()) {
                    Token candidate = tokens.get(next);
                    if (candidate.kind() == Kind.KEYWORD
                            || (candidate.kind() == Kind.PLAIN
                                    && RustText.trim(candidate.text()).isEmpty())) {
                        next++;
                    } else {
                        break;
                    }
                }
                if (next < tokens.size()) {
                    String name = tokenName(tokens.get(next));
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    static void recordRenderedDefinitions(
            JumpIndex index, String phase, String file, String source, SortedSet<Long> renderedLines) {
        HighlightLang lang = highlightLangForPath(file);
        List<String> lines = RustText.lines(source);
        for (long lineNo : renderedLines) {
            if (lineNo < 1 || lineNo > lines.size()) {
                continue;
            }
            for (String name : definedNames(lang, lines.get((int) (lineNo - 1)))) {
                index.insert(phase, file, lineNo, name);
            }
        }
    }

    private static long signalCount(String source, List<String> signals) {
        long total = 0;
        for (String signal : signals) {
            int from = 0;
            while (true) {
                int at = source.indexOf(signal, from);
                if (at < 0) {
                    break;
                }
                total++;
                from = at + signal.length();
            }
        }
        return total;
    }

    private static @Nullable String tokenName(Token token) {
        return switch (token.kind()) {
            case CALL, TYPE, IDENT -> token.text();
            default -> null;
        };
    }

    private static String renderToken(HighlightLang lang, Token token, Function<String, @Nullable String> hrefFor) {
        return switch (token.kind()) {
            case PLAIN -> escape(token.text());
            case COMMENT -> colored("tok-cmt", token.text());
            case STRING -> colored("tok-str", token.text());
            case NUMBER -> colored("tok-num", token.text());
            case ATTRIBUTE -> colored("tok-attr", token.text());
            case KEYWORD -> colored("tok-kw", token.text());
            case MACRO -> colored("tok-mac", token.text());
            case CALL -> linked(lang, "tok-fnc", token.text(), hrefFor);
            case TYPE -> linked(lang, "tok-ty", token.text(), hrefFor);
            case IDENT -> linked(lang, "", token.text(), hrefFor);
        };
    }

    private static String colored(String cssClass, String text) {
        return "<span class=\"" + cssClass + "\">" + escape(text) + "</span>";
    }

    private static String linked(
            HighlightLang lang, String cssClass, String text, Function<String, @Nullable String> hrefFor) {
        String body = cssClass.isEmpty() ? escape(text) : colored(cssClass, text);
        if (lang != HighlightLang.RUST && lang != HighlightLang.TYPESCRIPT) {
            return body;
        }
        String href = hrefFor.apply(text);
        if (href != null && safeHref(href)) {
            return "<a class=\"sym\" href=\"" + href + "\" title=\"Command-click to jump to the covered definition\">"
                    + body + "</a>";
        }
        return body;
    }

    private static boolean safeHref(String href) {
        String id = href.startsWith("phases.html#")
                ? href.substring("phases.html#".length())
                : href.startsWith("#") ? href.substring(1) : null;
        if (id == null || id.isEmpty()) {
            return false;
        }
        return id.codePoints().allMatch(character -> RustChar.isAsciiAlphanumeric(character) || character == '-');
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<Token> scan(HighlightLang lang, String line) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        int plainStart = 0;
        while (index < line.length()) {
            Taken taken = takeSpecial(lang, line, index);
            if (taken != null) {
                if (plainStart < index) {
                    tokens.add(new Token(Kind.PLAIN, line.substring(plainStart, index)));
                }
                tokens.add(taken.token());
                index = taken.next();
                plainStart = index;
                continue;
            }
            index += Character.charCount(line.codePointAt(index));
        }
        if (plainStart < line.length()) {
            tokens.add(new Token(Kind.PLAIN, line.substring(plainStart)));
        }
        return tokens;
    }

    private static @Nullable Taken takeSpecial(HighlightLang lang, String line, int index) {
        if (lang == HighlightLang.PLAIN) {
            return null;
        }
        String rest = line.substring(index);
        boolean code = lang == HighlightLang.RUST || lang == HighlightLang.TYPESCRIPT;
        if (lang == HighlightLang.RUST && (rest.startsWith("#[") || rest.startsWith("#!["))) {
            return new Taken(new Token(Kind.ATTRIBUTE, rest), line.length());
        }
        if (code && rest.startsWith("//")) {
            return new Taken(new Token(Kind.COMMENT, rest), line.length());
        }
        if (code && rest.startsWith("/*")) {
            int at = rest.indexOf("*/");
            int end = at < 0 ? line.length() : index + at + 2;
            return new Taken(new Token(Kind.COMMENT, line.substring(index, end)), end);
        }
        int stringEnd = takeString(lang, line, index);
        if (stringEnd >= 0) {
            return new Taken(new Token(Kind.STRING, line.substring(index, stringEnd)), stringEnd);
        }
        if (lang == HighlightLang.RUST) {
            int lifetimeEnd = takeLifetime(line, index);
            if (lifetimeEnd >= 0) {
                return new Taken(new Token(Kind.PLAIN, line.substring(index, lifetimeEnd)), lifetimeEnd);
            }
        }
        if (lang == HighlightLang.CONFIG && rest.startsWith("#")) {
            return new Taken(new Token(Kind.COMMENT, rest), line.length());
        }
        int numberEnd = takeNumber(line, index);
        if (numberEnd >= 0) {
            return new Taken(new Token(Kind.NUMBER, line.substring(index, numberEnd)), numberEnd);
        }
        return takeIdent(lang, line, index);
    }

    private static int takeString(HighlightLang lang, String line, int index) {
        return switch (lang) {
            case PLAIN -> -1;
            case RUST -> takeRustString(line, index);
            case TYPESCRIPT -> takeQuoted(line, index, "\"'`");
            case CONFIG -> takeQuoted(line, index, "\"'");
        };
    }

    private static int charAt(String line, int index) {
        return index < line.length() ? line.charAt(index) : -1;
    }

    private static int takeRustString(String line, int index) {
        int cursor = index;
        int first = charAt(line, cursor);
        int second = charAt(line, cursor + 1);
        if ((first == 'b' || first == 'c') && (second == 'r' || second == '"' || second == '\'')) {
            cursor++;
        }
        return switch (charAt(line, cursor)) {
            case 'r' -> takeRawString(line, cursor);
            case '"' -> takeEscapedQuote(line, cursor, '"');
            case '\'' -> takeChar(line, cursor);
            default -> -1;
        };
    }

    private static int takeRawString(String line, int rIndex) {
        if (charAt(line, rIndex) != 'r') {
            return -1;
        }
        int cursor = rIndex + 1;
        int hashes = 0;
        while (charAt(line, cursor) == '#') {
            hashes++;
            cursor++;
        }
        if (charAt(line, cursor) != '"') {
            return -1;
        }
        cursor++;
        String closer = "\"" + "#".repeat(hashes);
        int at = line.indexOf(closer, cursor);
        return at < 0 ? line.length() : at + closer.length();
    }

    private static int takeQuoted(String line, int index, String quotes) {
        if (index >= line.length()) {
            return -1;
        }
        int first = line.codePointAt(index);
        if (quotes.indexOf(first) >= 0) {
            return takeEscapedQuote(line, index, first);
        }
        return -1;
    }

    private static int takeEscapedQuote(String line, int quoteIndex, int quote) {
        if (quoteIndex >= line.length() || line.codePointAt(quoteIndex) != quote) {
            return -1;
        }
        int end = quoteIndex + Character.charCount(quote);
        boolean escaped = false;
        while (end < line.length()) {
            int character = line.codePointAt(end);
            end += Character.charCount(character);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == quote) {
                return end;
            }
        }
        return line.length();
    }

    private static int takeChar(String line, int index) {
        if (charAt(line, index) != '\'') {
            return -1;
        }
        int afterQuote = index + 1;
        if (afterQuote >= line.length()) {
            return -1;
        }
        int next = line.codePointAt(afterQuote);
        if (next == '\\') {
            return takeEscapedQuote(line, index, '\'');
        }
        int closeAt = afterQuote + Character.charCount(next);
        if (charAt(line, closeAt) == '\'') {
            return closeAt + 1;
        }
        return -1;
    }

    private static int takeLifetime(String line, int index) {
        if (charAt(line, index) != '\'') {
            return -1;
        }
        int start = index + 1;
        if (start >= line.length()) {
            return -1;
        }
        int first = line.codePointAt(start);
        if (!isIdentStart(first)) {
            return -1;
        }
        int end = start + Character.charCount(first);
        while (end < line.length()) {
            int character = line.codePointAt(end);
            if (!isIdentContinue(character)) {
                break;
            }
            end += Character.charCount(character);
        }
        return end;
    }

    private static int takeNumber(String line, int index) {
        if (index >= line.length()) {
            return -1;
        }
        int first = line.codePointAt(index);
        if (!RustChar.isAsciiDigit(first)) {
            return -1;
        }
        int end = index + 1;
        if (first == '0' && end < line.length()) {
            int marker = line.codePointAt(end);
            if (marker == 'x' || marker == 'X' || marker == 'b' || marker == 'o') {
                end += Character.charCount(marker);
                while (end < line.length()) {
                    int character = line.codePointAt(end);
                    if (RustChar.isAsciiHexdigit(character) || character == '_') {
                        end += 1;
                    } else {
                        break;
                    }
                }
                return end;
            }
        }
        while (end < line.length()) {
            int character = line.codePointAt(end);
            if (RustChar.isAsciiDigit(character) || character == '_') {
                end += 1;
                continue;
            }
            if (character == '.' && end + 1 < line.length() && RustChar.isAsciiDigit(line.codePointAt(end + 1))) {
                end += 1;
                continue;
            }
            break;
        }
        return end;
    }

    private static @Nullable Taken takeIdent(HighlightLang lang, String line, int index) {
        if (index >= line.length()) {
            return null;
        }
        int first = line.codePointAt(index);
        if (!isIdentStart(first)) {
            return null;
        }
        int end = index + Character.charCount(first);
        while (end < line.length()) {
            int character = line.codePointAt(end);
            if (!isIdentContinue(character)) {
                break;
            }
            end += Character.charCount(character);
        }
        String ident = line.substring(index, end);
        if (isKeyword(lang, ident)) {
            return new Taken(new Token(Kind.KEYWORD, ident), end);
        }
        if (isPrimitiveType(lang, ident)) {
            return new Taken(new Token(Kind.TYPE, ident), end);
        }
        int after = skipWs(line, end);
        int next = after < line.length() ? line.codePointAt(after) : -1;
        int afterNext = next < 0 || after + Character.charCount(next) >= line.length()
                ? -1
                : line.codePointAt(after + Character.charCount(next));
        if (next == '!' && afterNext != '=' && lang == HighlightLang.RUST) {
            int bangEnd = after + 1;
            return new Taken(new Token(Kind.MACRO, line.substring(index, bangEnd)), bangEnd);
        }
        boolean code = lang == HighlightLang.RUST || lang == HighlightLang.TYPESCRIPT;
        if (next == '(' && code) {
            return new Taken(new Token(Kind.CALL, ident), end);
        }
        if (RustChar.isUppercase(first) && code) {
            return new Taken(new Token(Kind.TYPE, ident), end);
        }
        return new Taken(new Token(Kind.IDENT, ident), end);
    }

    private static int skipWs(String line, int index) {
        int end = index;
        while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
            end++;
        }
        return end;
    }

    private static boolean isIdentStart(int character) {
        return character == '_' || RustChar.isAlphabetic(character);
    }

    private static boolean isIdentContinue(int character) {
        return character == '_' || RustChar.isAlphanumeric(character);
    }

    private static boolean isDefKeyword(HighlightLang lang, String word) {
        return switch (lang) {
            case RUST ->
                switch (word) {
                    case "fn", "struct", "enum", "trait", "type", "const", "static", "mod" -> true;
                    default -> false;
                };
            case TYPESCRIPT ->
                switch (word) {
                    case "function", "class", "interface", "type", "enum" -> true;
                    default -> false;
                };
            case PLAIN, CONFIG -> false;
        };
    }

    private static boolean isPrimitiveType(HighlightLang lang, String word) {
        if (lang != HighlightLang.RUST) {
            return false;
        }
        return switch (word) {
            case "bool",
                    "char",
                    "str",
                    "u8",
                    "u16",
                    "u32",
                    "u64",
                    "u128",
                    "usize",
                    "i8",
                    "i16",
                    "i32",
                    "i64",
                    "i128",
                    "isize",
                    "f32",
                    "f64" -> true;
            default -> false;
        };
    }

    private static boolean isKeyword(HighlightLang lang, String word) {
        return switch (lang) {
            case RUST ->
                switch (word) {
                    case "as",
                            "async",
                            "await",
                            "break",
                            "const",
                            "continue",
                            "crate",
                            "dyn",
                            "else",
                            "enum",
                            "extern",
                            "false",
                            "fn",
                            "for",
                            "if",
                            "impl",
                            "in",
                            "let",
                            "loop",
                            "match",
                            "mod",
                            "move",
                            "mut",
                            "pub",
                            "ref",
                            "return",
                            "self",
                            "Self",
                            "static",
                            "struct",
                            "super",
                            "trait",
                            "true",
                            "type",
                            "unsafe",
                            "use",
                            "where",
                            "while" -> true;
                    default -> false;
                };
            case TYPESCRIPT ->
                switch (word) {
                    case "as",
                            "async",
                            "await",
                            "break",
                            "case",
                            "catch",
                            "class",
                            "const",
                            "continue",
                            "debugger",
                            "default",
                            "delete",
                            "do",
                            "else",
                            "enum",
                            "export",
                            "extends",
                            "false",
                            "finally",
                            "for",
                            "from",
                            "function",
                            "if",
                            "implements",
                            "import",
                            "in",
                            "instanceof",
                            "interface",
                            "let",
                            "new",
                            "null",
                            "of",
                            "private",
                            "protected",
                            "public",
                            "readonly",
                            "return",
                            "satisfies",
                            "static",
                            "super",
                            "switch",
                            "this",
                            "throw",
                            "true",
                            "try",
                            "type",
                            "typeof",
                            "undefined",
                            "var",
                            "void",
                            "while",
                            "yield" -> true;
                    default -> false;
                };
            case PLAIN, CONFIG -> false;
        };
    }
}
