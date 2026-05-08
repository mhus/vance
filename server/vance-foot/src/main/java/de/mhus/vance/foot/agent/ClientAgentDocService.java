package de.mhus.vance.foot.agent;

import de.mhus.vance.api.ws.ClientAgentUploadRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Reads a project agent doc from the foot process's current working
 * directory and pushes it to the brain via
 * {@link MessageType#CLIENT_AGENT_UPLOAD} so it can be spliced into the
 * conversation's memory block.
 *
 * <p>Lookup cascade (first hit wins):
 * <ol>
 *   <li>An override path set via {@link #setOverridePath(Path)} — wired up
 *       by {@code chat --agent-file=<path>}. A non-existent override is an
 *       error: nothing is uploaded and a warning is logged. We do
 *       <em>not</em> silently fall through to the defaults, because the
 *       user explicitly asked for that file.</li>
 *   <li>{@code ./agent.md}</li>
 *   <li>{@code ./CLAUDE.md} — convenience fallback so projects already
 *       documented for Claude Code feed straight into Vance.</li>
 * </ol>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Triggered automatically on every session-bind by
 *       {@link de.mhus.vance.foot.session.SessionService}.</li>
 *   <li>Manual re-upload via the {@code /reload} slash-command — useful
 *       after editing the doc mid-session.</li>
 * </ol>
 *
 * <p>Failure modes are quiet by design: file missing is the common case
 * (most cwds have neither {@code agent.md} nor {@code CLAUDE.md}) and just
 * results in no upload. Read errors and oversize files log a warning but
 * never crash the connect or REPL.
 *
 * <p>Whether the brain actually uses the uploaded doc is a separate
 * decision driven by the active recipe's profile-block — see
 * {@code MemoryContextLoader.USE_CLIENT_AGENT_DOC_PARAM}.
 */
@Service
@Slf4j
public class ClientAgentDocService {

    /** Default file names tried in order when no override is set. */
    public static final List<String> DEFAULT_DOC_FILENAMES = List.of("agent.md", "CLAUDE.md");

    /** Soft size cap. Anything larger is dropped with a warning. */
    public static final int MAX_DOC_BYTES = 64 * 1024;

    private final ObjectProvider<ConnectionService> connectionProvider;
    private final ObjectProvider<ChatTerminal> terminalProvider;
    private final AtomicReference<@Nullable Path> overridePath = new AtomicReference<>();

    public ClientAgentDocService(ObjectProvider<ConnectionService> connectionProvider,
                                 ObjectProvider<ChatTerminal> terminalProvider) {
        this.connectionProvider = connectionProvider;
        this.terminalProvider = terminalProvider;
    }

    /**
     * Pin the doc to a specific path (resolved against cwd if relative).
     * Pass {@code null} to clear the override and fall back to the
     * {@code agent.md} → {@code CLAUDE.md} cascade.
     */
    public void setOverridePath(@Nullable Path path) {
        overridePath.set(path == null ? null : path.toAbsolutePath().normalize());
    }

    /**
     * Reads the resolved doc (if present) and uploads its contents.
     * No-op when not connected, when no doc is found, or when the file
     * exceeds the size cap. Returns {@code true} when an upload was
     * actually sent (success ack received).
     */
    public boolean uploadIfPresent() {
        ConnectionService connection = connectionProvider.getIfAvailable();
        if (connection == null || !connection.isOpen()) {
            log.debug("ClientAgentDocService.uploadIfPresent — not connected, skipped");
            return false;
        }
        Path path = resolvedPath();
        if (path == null) {
            Path override = overridePath.get();
            if (override != null) {
                log.warn("--agent-file points at {} which does not exist — nothing uploaded", override);
                terminalInfo("agent doc: --agent-file=" + override + " not found — nothing uploaded");
            } else {
                log.debug("No agent.md or CLAUDE.md in cwd — skipping client-agent-upload");
            }
            return false;
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.toString());
            return false;
        }
        if (content.length() > MAX_DOC_BYTES) {
            log.warn("{} exceeds size cap ({} > {}) — not uploaded",
                    path, content.length(), MAX_DOC_BYTES);
            return false;
        }
        ClientAgentUploadRequest request = ClientAgentUploadRequest.builder()
                .path("./" + path.getFileName())
                .content(content)
                .build();
        try {
            connection.request(
                    MessageType.CLIENT_AGENT_UPLOAD,
                    request,
                    Object.class,
                    Duration.ofSeconds(10));
            log.info("client-agent-upload: pushed {} ({} chars) to brain",
                    path, content.length());
            terminalInfo("agent doc: uploaded " + path.getFileName()
                    + " (" + content.length() + " chars)");
            return true;
        } catch (Exception e) {
            log.warn("client-agent-upload failed: {}", e.toString());
            terminalInfo("agent doc: upload failed — " + e.getMessage());
            return false;
        }
    }

    private void terminalInfo(String message) {
        ChatTerminal terminal = terminalProvider.getIfAvailable();
        if (terminal != null) {
            terminal.info(message);
        }
    }

    /**
     * Resolved path of the doc that {@link #uploadIfPresent()} would send,
     * or {@code null} when nothing is found. Override wins over defaults;
     * a non-existent override returns {@code null} (does not fall through).
     */
    public @Nullable Path resolvedPath() {
        return resolveIn(Paths.get("").toAbsolutePath());
    }

    /**
     * Same as {@link #resolvedPath()} but resolves default filenames
     * against an explicit base directory instead of the JVM's cwd.
     * Exposed for tests where the JVM cwd cannot be flipped at runtime.
     */
    public @Nullable Path resolveIn(Path base) {
        Path override = overridePath.get();
        if (override != null) {
            return Files.isRegularFile(override) ? override : null;
        }
        for (String name : DEFAULT_DOC_FILENAMES) {
            Path candidate = base.resolve(name).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
