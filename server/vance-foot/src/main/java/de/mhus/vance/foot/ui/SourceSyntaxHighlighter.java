package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import java.util.Set;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;

/** Lightweight stateful Java/Python/Shell lexer which changes foreground colours only. */
public final class SourceSyntaxHighlighter {

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "exports", "extends",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "module", "native", "new", "non-sealed", "open", "opens",
            "package", "permits", "private", "protected", "provides", "public", "record", "requires",
            "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "to", "transient", "transitive", "try", "uses", "var", "void",
            "volatile", "when", "while", "with", "yield", "true", "false", "null");
    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
            "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if",
            "import", "in", "is", "lambda", "match", "None", "nonlocal", "not", "or", "pass",
            "raise", "return", "True", "try", "while", "with", "yield");
    private static final Set<String> SHELL_KEYWORDS = Set.of(
            "case", "coproc", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if",
            "in", "select", "then", "time", "until", "while");
    private static final Set<String> SHELL_BUILTINS = Set.of(
            "alias", "bg", "bind", "break", "builtin", "caller", "cd", "command", "compgen", "complete",
            "continue", "declare", "dirs", "disown", "echo", "enable", "eval", "exec", "exit", "export",
            "false", "fc", "fg", "getopts", "hash", "help", "history", "jobs", "kill", "let", "local",
            "logout", "mapfile", "popd", "printf", "pushd", "pwd", "read", "readonly", "return", "set",
            "shift", "shopt", "source", "suspend", "test", "times", "trap", "true", "type", "typeset",
            "ulimit", "umask", "unalias", "unset", "wait");

    public static final class State {
        private boolean javaBlockComment;
        private @Nullable String pythonTripleQuote;
        private @Nullable Character shellQuote;
    }

    private final @Nullable AttributedStyle keyword;
    private final @Nullable AttributedStyle string;
    private final @Nullable AttributedStyle comment;
    private final @Nullable AttributedStyle number;
    private final @Nullable AttributedStyle annotation;

    public SourceSyntaxHighlighter(FootConfig.SyntaxHighlight cfg) {
        keyword = StyleParser.parse(cfg.getKeyword());
        string = StyleParser.parse(cfg.getString());
        comment = StyleParser.parse(cfg.getComment());
        number = StyleParser.parse(cfg.getNumber());
        annotation = StyleParser.parse(cfg.getAnnotation());
    }

    public AttributedString highlight(String line, SourceLanguage language, State state) {
        AttributedStringBuilder out = new AttributedStringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (language == SourceLanguage.SHELL && state.shellQuote != null) {
                int end = shellQuotedEnd(line, i, state.shellQuote);
                append(out, line.substring(i, end), string);
                if (end <= line.length() && end > i && line.charAt(end - 1) == state.shellQuote) {
                    state.shellQuote = null;
                }
                i = end;
                continue;
            }
            if (language == SourceLanguage.JAVA && state.javaBlockComment) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    append(out, line.substring(i), comment);
                    return out.toAttributedString();
                }
                append(out, line.substring(i, end + 2), comment);
                state.javaBlockComment = false;
                i = end + 2;
                continue;
            }
            if (language == SourceLanguage.PYTHON && state.pythonTripleQuote != null) {
                int end = line.indexOf(state.pythonTripleQuote, i);
                if (end < 0) {
                    append(out, line.substring(i), string);
                    return out.toAttributedString();
                }
                int next = end + 3;
                append(out, line.substring(i, next), string);
                state.pythonTripleQuote = null;
                i = next;
                continue;
            }
            if (language == SourceLanguage.JAVA && line.startsWith("//", i)
                    || language == SourceLanguage.PYTHON && line.charAt(i) == '#'
                    || language == SourceLanguage.SHELL && line.charAt(i) == '#') {
                append(out, line.substring(i), comment);
                break;
            }
            if (language == SourceLanguage.JAVA && line.startsWith("/*", i)) {
                int end = line.indexOf("*/", i + 2);
                if (end < 0) {
                    append(out, line.substring(i), comment);
                    state.javaBlockComment = true;
                    break;
                }
                append(out, line.substring(i, end + 2), comment);
                i = end + 2;
                continue;
            }
            if (language == SourceLanguage.PYTHON
                    && (line.startsWith("'''", i) || line.startsWith("\"\"\"", i))) {
                String quote = line.substring(i, i + 3);
                int end = line.indexOf(quote, i + 3);
                if (end < 0) {
                    append(out, line.substring(i), string);
                    state.pythonTripleQuote = quote;
                    break;
                }
                append(out, line.substring(i, end + 3), string);
                i = end + 3;
                continue;
            }
            char c = line.charAt(i);
            if (language == SourceLanguage.SHELL && c == '$') {
                int end = shellVariableEnd(line, i);
                append(out, line.substring(i, end), annotation);
                i = end;
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = language == SourceLanguage.SHELL
                        ? shellQuotedEnd(line, i + 1, c)
                        : quotedEnd(line, i, c);
                append(out, line.substring(i, end), string);
                if (language == SourceLanguage.SHELL && (end == line.length()
                        && (line.isEmpty() || line.charAt(end - 1) != c))) {
                    state.shellQuote = c;
                }
                i = end;
                continue;
            }
            if (language == SourceLanguage.JAVA && c == '@') {
                int end = identifierEnd(line, i + 1);
                append(out, line.substring(i, end), annotation);
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < line.length()) {
                    char n = line.charAt(end);
                    if (!Character.isLetterOrDigit(n) && n != '.' && n != '_') break;
                    end++;
                }
                append(out, line.substring(i, end), number);
                i = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(line, i + 1);
                String word = line.substring(i, end);
                Set<String> words = switch (language) {
                    case JAVA -> JAVA_KEYWORDS;
                    case PYTHON -> PYTHON_KEYWORDS;
                    case SHELL -> SHELL_KEYWORDS;
                };
                AttributedStyle wordStyle = words.contains(word) ? keyword : null;
                if (language == SourceLanguage.SHELL && SHELL_BUILTINS.contains(word)) wordStyle = annotation;
                append(out, word, wordStyle);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toAttributedString();
    }

    private static int shellVariableEnd(String line, int start) {
        int next = start + 1;
        if (next >= line.length()) return next;
        char first = line.charAt(next);
        if (first == '{') {
            int end = line.indexOf('}', next + 1);
            return end < 0 ? line.length() : end + 1;
        }
        if (first == '(') {
            int depth = 1;
            for (int i = next + 1; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '(') depth++;
                if (c == ')' && --depth == 0) return i + 1;
            }
            return line.length();
        }
        if (Character.isDigit(first) || "@*#?$!-_".indexOf(first) >= 0) return next + 1;
        while (next < line.length()) {
            char c = line.charAt(next);
            if (!Character.isLetterOrDigit(c) && c != '_') break;
            next++;
        }
        return next == start + 1 ? start + 1 : next;
    }

    private static int shellQuotedEnd(String line, int start, char quote) {
        boolean escaped = false;
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!escaped && c == quote) return i + 1;
            if (quote == '\'' && c == '\\') continue;
            escaped = !escaped && c == '\\';
            if (c != '\\') escaped = false;
        }
        return line.length();
    }

    private static int quotedEnd(String line, int start, char quote) {
        boolean escaped = false;
        for (int i = start + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!escaped && c == quote) return i + 1;
            escaped = !escaped && c == '\\';
            if (c != '\\') escaped = false;
        }
        return line.length();
    }

    private static int identifierEnd(String line, int start) {
        int i = start;
        while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) i++;
        return i;
    }

    private static void append(AttributedStringBuilder out, String text, @Nullable AttributedStyle style) {
        if (style == null) {
            out.append(text);
        } else {
            AttributedStyle previous = out.style();
            out.style(style).append(text).style(previous);
        }
    }
}
