package de.mhus.vance.brain.tools.notification;

import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server tool {@code vance_notify} — fires an attention-grabbing push
 * (terminal bell / WebAudio beep / iOS local-notification) on the
 * client(s) bound to the calling process's session.
 *
 * <p>Use sparingly: only at notable boundaries (process done, long wait
 * resolved, escalation). Status chatter belongs in the progress
 * side-channel — call {@code vance.process.progress(...)} for that.
 *
 * <p>The notification is flüchtig: if the user has no live client when
 * it fires, it is dropped. Persistence is the inbox's job
 * ({@code inbox_post}).
 *
 * <p>Spec: {@code specification/user-notification-channel.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotifyTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "text", Map.of(
                            "type", "string",
                            "description", "Short attention text — recommend "
                                    + "≤120 chars. Shown verbatim on the client."),
                    "severity", Map.of(
                            "type", "string",
                            "enum", List.of("INFO", "WARN", "ERROR"),
                            "description", "INFO (default, heads-up), WARN "
                                    + "(needs attention soon), ERROR (failure / "
                                    + "escalation). Drives client-side sound + "
                                    + "icon, never suppresses the ping itself.")),
            "required", List.of("text"));

    private final NotificationService notificationService;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "vance_notify";
    }

    @Override
    public String description() {
        return "Fire a short attention-grabbing notification on the "
                + "user's client (beep + toast / system notification). "
                + "Use only at notable boundaries — process done, long "
                + "wait resolved, escalation. Status chatter belongs in "
                + "the progress side-channel. Notifications are flüchtig: "
                + "no inbox entry, no replay if the user is offline. Use "
                + "inbox_post if persistence is required.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("notification");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String processId = ctx.processId();
        if (processId == null) {
            throw new ToolException(
                    "vance_notify requires a process scope — no processId in context");
        }
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
        if (processOpt.isEmpty()) {
            throw new ToolException(
                    "vance_notify: process '" + processId + "' not found");
        }
        String text = stringOrThrow(params, "text");
        NotificationSeverity severity = parseSeverity(optString(params, "severity"));

        boolean delivered = notificationService.publish(processOpt.get(), text, severity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delivered", delivered);
        out.put("severity", severity.name());
        return out;
    }

    private static NotificationSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) return NotificationSeverity.INFO;
        try {
            return NotificationSeverity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException(
                    "Unknown severity '" + raw + "' — expected INFO, WARN, or ERROR");
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof String s && !s.isBlank()) return s;
        throw new ToolException("Missing required parameter '" + key + "'");
    }

    private static String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
