package de.mhus.vance.shared.project;

import java.lang.StackWalker.StackFrame;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Diagnostic helper: locates the first Vance frame on the call stack above
 * {@code skip} frames. Used by service methods that need to log their caller
 * in conflict / warning paths without dragging the full stack into the log
 * line.
 *
 * <p>"Vance frame" = any class whose fully-qualified name starts with
 * {@code de.mhus.vance.} but is not this helper itself (the helper lives in
 * {@code de.mhus.vance.shared.project}, so the second filter is needed when
 * called from the same package) and not a generated Spring proxy
 * ({@code $$SpringCGLIB$$}), which only forwards to the real caller.
 *
 * <p>Returns the frame as a {@link StackTraceElement#toString()} string —
 * {@code "<class>.<method>(<file>:<line>)"} — which is what the log line
 * wants to embed.
 */
public final class VanceCallerLocator {

    private VanceCallerLocator() {}

    /**
     * Returns the first Vance frame at depth {@code >= skip}, or {@code null}
     * if no Vance frame is found above {@code skip}.
     *
     * @param skip number of frames to skip before scanning; frame 0 is this
     *     helper, frame 1 its caller. At minimum 1. A service method that
     *     wants <em>its</em> caller passes 2.
     */
    public static @Nullable String firstVanceFrame(int skip) {
        int start = Math.max(1, skip);
        StackWalker walker = StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE));
        return walker.walk(frames -> frames.skip(start)
                .map(StackFrame::toStackTraceElement)
                .filter(VanceCallerLocator::isVanceFrame)
                .findFirst()
                .map(StackTraceElement::toString)
                .orElse(null));
    }

    static boolean isVanceFrame(StackTraceElement e) {
        String cn = e.getClassName();
        if (cn.contains("$$")) return false;
        // Exclude this helper class itself — but only the helper, not its
        // sibling test class `VanceCallerLocatorTest` (String#startsWith has
        // no notion of word boundaries).
        return cn.startsWith("de.mhus.vance.") && !cn.equals("de.mhus.vance.shared.project.VanceCallerLocator");
    }
}
