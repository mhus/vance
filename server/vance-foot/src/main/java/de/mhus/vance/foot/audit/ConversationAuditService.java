package de.mhus.vance.foot.audit;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends every chat message (USER and ASSISTANT) as a JSON line to a
 * per-session {@code .jsonl} file, so the full conversation is persisted
 * on disk as it happens — live, one open-append-close cycle per message.
 *
 * <p>Files land under
 * {@code <baseDir>/<YYYY>-<MM>/<sessionId>.jsonl}. The base directory
 * resolves to {@code <active-.vancetope>/<configured-dir>} (default
 * {@code conversations}). The year-month partition is derived from the
 * wall-clock at write time.
 *
 * <p>Enabled/disabled is driven by
 * {@link FootConfig.ConversationAudit#isEnabled()}, which is overlaid by
 * {@code .vancetope/config.yaml} and CLI flags ({@code --audit} /
 * {@code --no-audit}) before this service is first called.
 *
 * <p>Best-effort: I/O errors are logged at WARN and swallowed — the
 * conversation must never break because the audit file couldn't be
 * written.
 */
@Service
@Slf4j
public class ConversationAuditService {

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneId.systemDefault());

    private final FootConfig config;
    private final VancePaths vancePaths;
    private final SessionService sessions;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Clock clock;

    @Autowired
    public ConversationAuditService(FootConfig config,
                                    VancePaths vancePaths,
                                    SessionService sessions) {
        this(config, vancePaths, sessions, Clock.systemDefaultZone());
    }

    /** Test constructor with a custom clock. */
    ConversationAuditService(FootConfig config,
                             VancePaths vancePaths,
                             SessionService sessions,
                             Clock clock) {
        this.config = config;
        this.vancePaths = vancePaths;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Appends a single chat message to the audit file. No-op when the
     * feature is disabled or no session is bound.
     *
     * @param data the {@code chat-message-appended} payload — carries
     *             role, content, process name, timestamps, and optional
     *             sender metadata
     */
    public void append(ChatMessageAppendedData data) {
        if (!isEnabled()) return;

        SessionService.BoundSession bound = sessions.current();
        if (bound == null) return;

        String sessionId = bound.sessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        Path file = resolveAuditFile(sessionId);
        if (file == null) return;

        String jsonLine = buildJsonLine(data, sessionId);
        try {
            Files.writeString(file, jsonLine + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write conversation audit to {}: {}", file, e.getMessage());
        }
    }

    /** Whether audit logging is currently active. */
    public boolean isEnabled() {
        return config.getConversationAudit().isEnabled();
    }

    /**
     * Resolves the JSONL file path for the given session:
     * {@code <baseDir>/<YYYY>-<MM>/<sessionId>.jsonl}. Creates the
     * month directory if needed. Returns {@code null} when the base
     * directory can't be resolved or created.
     */
    private @Nullable Path resolveAuditFile(String sessionId) {
        Path baseDir = resolveBaseDir();
        if (baseDir == null) return null;

        String monthDir = MONTH_FMT.format(Instant.now(clock));
        Path dir = baseDir.resolve(monthDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create conversation audit directory {}: {}", dir, e.getMessage());
            return null;
        }
        return dir.resolve(sessionId + ".jsonl");
    }

    /**
     * Resolves the base directory for audit files: the active
     * {@code .vancetope} directory joined with the configured sub-dir
     * (default {@code conversations}). Returns {@code null} when the
     * active directory can't be determined.
     */
    private @Nullable Path resolveBaseDir() {
        Path activeDir = vancePaths.activeDir();
        String configured = config.getConversationAudit().getDir();
        String subDir = (configured == null || configured.isBlank()) ? "conversations" : configured.trim();
        return activeDir.resolve(subDir);
    }

    /**
     * Builds the JSON line for a single message. The structure is
     * intentionally flat and self-describing — a superset of the
     * {@code chat-message-appended} payload plus the session id and a
     * local write timestamp.
     */
    private String buildJsonLine(ChatMessageAppendedData data, String sessionId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now(clock).toString());
        entry.put("sessionId", sessionId);
        entry.put("chatMessageId", data.getChatMessageId());
        entry.put("thinkProcessId", data.getThinkProcessId());
        entry.put("processName", data.getProcessName());
        ChatRole role = data.getRole();
        entry.put("role", role == null ? null : role.name().toLowerCase());
        entry.put("content", data.getContent());
        if (data.getThinking() != null) {
            entry.put("thinking", data.getThinking());
        }
        if (data.getCreatedAt() != null) {
            entry.put("createdAt", data.getCreatedAt().toString());
        }
        if (data.getSenderUserId() != null) {
            entry.put("senderUserId", data.getSenderUserId());
        }
        if (data.getSenderDisplayName() != null) {
            entry.put("senderDisplayName", data.getSenderDisplayName());
        }
        if (data.getMeta() != null && !data.getMeta().isEmpty()) {
            entry.put("meta", data.getMeta());
        }
        try {
            return json.writeValueAsString(entry);
        } catch (Exception e) {
            // Should never happen with a plain Map — fall back to a
            // minimal line so the audit is never silently lost.
            log.warn("Failed to serialize conversation audit entry: {}", e.getMessage());
            return "{\"timestamp\":\"" + Instant.now(clock)
                    + "\",\"sessionId\":\"" + sessionId
                    + "\",\"role\":\"" + (role == null ? "unknown" : role.name().toLowerCase())
                    + "\",\"content\":\"<serialization failed>\"}";
        }
    }
}
