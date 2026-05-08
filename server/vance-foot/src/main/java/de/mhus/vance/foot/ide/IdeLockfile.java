package de.mhus.vance.foot.ide;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Contents of a single Claude Code IDE plugin lockfile under
 * {@code ~/.claude/ide/<port>.lock}.
 *
 * <p>The plugin writes one file per running IDE window; we only consume them.
 * See {@code planning/foot-ide-bridge.md} §1.1 for the schema and §7 for the
 * rule that vance-foot must never write or delete files in this directory.
 *
 * @param port             port parsed from the file name
 * @param path             path to the lockfile itself
 * @param workspaceFolders absolute project roots open in this IDE window
 * @param pid              OS process id of the IDE
 * @param ideName          human-readable IDE name (e.g. "IntelliJ IDEA")
 * @param transport        "ws" (current) or "sse" (legacy)
 * @param runningInWindows true when the plugin reports a Windows host
 *                         (relevant for WSL connect targets, §1.4)
 * @param authToken        random secret used as the
 *                         {@code X-Claude-Code-Ide-Authorization} header
 */
public record IdeLockfile(
        int port,
        Path path,
        List<String> workspaceFolders,
        long pid,
        @Nullable String ideName,
        @Nullable String transport,
        boolean runningInWindows,
        String authToken) {
}
