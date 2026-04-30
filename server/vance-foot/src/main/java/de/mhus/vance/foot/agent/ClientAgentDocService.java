package de.mhus.vance.foot.agent;

import de.mhus.vance.api.ws.ClientAgentUploadRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Reads the user's local {@code ./agent.md} (relative to the foot
 * process's current working directory) and pushes it to the brain via
 * {@link MessageType#CLIENT_AGENT_UPLOAD} so it can be spliced into
 * the conversation's memory block.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Triggered automatically on every session-bind by
 *       {@link de.mhus.vance.foot.session.SessionService}.</li>
 *   <li>Manual re-upload via the {@code /reload} slash-command — useful
 *       after editing {@code agent.md} mid-session.</li>
 * </ol>
 *
 * <p>Failure modes are quiet by design: file missing is the common
 * case (most cwds don't have an {@code agent.md}) and just results in
 * no upload. Read errors and oversize files log a warning but never
 * crash the connect or REPL.
 *
 * <p>Whether the brain actually uses the uploaded doc is a separate
 * decision driven by the active recipe's profile-block — see
 * {@code MemoryContextLoader.USE_CLIENT_AGENT_DOC_PARAM}.
 */
@Service
@Slf4j
public class ClientAgentDocService {

    /** File name looked up relative to the foot process's cwd. */
    public static final String AGENT_DOC_FILENAME = "agent.md";

    /** Soft size cap. Anything larger is dropped with a warning. */
    public static final int MAX_DOC_BYTES = 64 * 1024;

    private final ObjectProvider<ConnectionService> connectionProvider;

    public ClientAgentDocService(ObjectProvider<ConnectionService> connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * Reads {@code ./agent.md} (if present) and uploads its contents.
     * No-op when not connected, when the file does not exist, or when
     * the file exceeds the size cap. Returns {@code true} when an
     * upload was actually sent (success ack received).
     */
    public boolean uploadIfPresent() {
        ConnectionService connection = connectionProvider.getIfAvailable();
        if (connection == null || !connection.isOpen()) {
            log.debug("ClientAgentDocService.uploadIfPresent — not connected, skipped");
            return false;
        }
        Path path = Paths.get(AGENT_DOC_FILENAME).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            log.debug("No {} in cwd ({}) — skipping client-agent-upload",
                    AGENT_DOC_FILENAME, path.getParent());
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
                .path("./" + AGENT_DOC_FILENAME)
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
            return true;
        } catch (Exception e) {
            log.warn("client-agent-upload failed: {}", e.toString());
            return false;
        }
    }

    /**
     * Resolved cwd path of the agent doc — for diagnostic display in
     * the {@code /reload} command. Returns {@code null} when the file
     * does not exist.
     */
    public @Nullable Path resolvedPath() {
        Path path = Paths.get(AGENT_DOC_FILENAME).toAbsolutePath().normalize();
        return Files.isRegularFile(path) ? path : null;
    }
}
