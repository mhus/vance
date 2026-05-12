package de.mhus.vance.brain.tools.python;

/**
 * POSIX shell single-quote escaping for arguments that flow into
 * {@code /bin/sh -c}. Wraps each input in single quotes and escapes
 * embedded single quotes by closing-escaping-reopening
 * ({@code '\\''}). Use for any LLM-supplied string that becomes part
 * of a shell command line.
 */
final class PythonShellEscape {

    private PythonShellEscape() {}

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
