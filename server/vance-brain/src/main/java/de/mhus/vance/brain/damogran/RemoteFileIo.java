package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.tools.ContextToolsApi;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * CLIENT/DAEMON {@link ComposeFileIo}: reads/writes files on the remote host via
 * the {@code file_read}/{@code file_write} work-target tools (the
 * {@link de.mhus.vance.brain.tools.worktarget.WorkTargetDispatcher} routes them
 * to the connected Foot / named daemon, since the process WorkTarget is set to
 * CLIENT/DAEMON). Text-oriented — content rides a UTF-8 string, so binary
 * import/export is best-effort only.
 */
final class RemoteFileIo implements ComposeFileIo {

    private final ContextToolsApi tools;

    RemoteFileIo(ContextToolsApi tools) {
        this.tools = tools;
    }

    @Override
    public boolean binaryCapable() {
        return false;
    }

    @Override
    public void writeBytes(String relativePath, byte[] bytes) {
        tools.invoke("file_write", Map.of(
                "path", relativePath, "content", new String(bytes, StandardCharsets.UTF_8)));
    }

    @Override
    public byte[] readBytes(String relativePath, long maxBytes) {
        Object content = tools.invoke("file_read", Map.of("path", relativePath)).get("content");
        return content == null ? new byte[0] : content.toString().getBytes(StandardCharsets.UTF_8);
    }
}
