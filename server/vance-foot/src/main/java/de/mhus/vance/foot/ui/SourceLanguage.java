package de.mhus.vance.foot.ui;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Languages supported by Foot's lightweight source highlighter. */
public enum SourceLanguage {
    JAVA,
    PYTHON,
    SHELL;

    /** Detect a language from a file path. Unknown extensions return {@code null}. */
    public static @Nullable SourceLanguage fromPath(@Nullable String path) {
        if (path == null) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return JAVA;
        if (lower.endsWith(".py") || lower.endsWith(".pyw")) return PYTHON;
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")
                || lower.endsWith(".ksh")) return SHELL;
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String name = lower.substring(slash + 1);
        if (name.equals("bashrc") || name.equals(".bashrc") || name.equals("bash_profile")
                || name.equals(".bash_profile") || name.equals("zshrc") || name.equals(".zshrc")
                || name.equals("profile") || name.equals(".profile")) return SHELL;
        return null;
    }

    /** Detect a language from the info string following a Markdown fence. */
    public static @Nullable SourceLanguage fromFence(@Nullable String info) {
        if (info == null) return null;
        String value = info.strip().toLowerCase(Locale.ROOT);
        int separator = value.indexOf(' ');
        if (separator >= 0) value = value.substring(0, separator);
        return switch (value) {
            case "java" -> JAVA;
            case "python", "py" -> PYTHON;
            case "bash", "sh", "shell", "shellscript", "zsh", "ksh" -> SHELL;
            default -> null;
        };
    }
}
