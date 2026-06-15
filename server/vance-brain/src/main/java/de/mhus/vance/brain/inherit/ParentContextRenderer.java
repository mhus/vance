package de.mhus.vance.brain.inherit;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.prak.SpanStrength;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders the {@code ## Parent context} block prepended to a freshly
 * spawned worker's prompt. The block reuses the parent process's
 * active {@code ARCHIVED_CHAT} memory summary (so older turns are
 * compressed by the existing compaction machinery rather than dumped
 * raw) plus a strength-filtered view of the parent's active history.
 *
 * <p>Same helper backs the on-demand {@code process_history_text}
 * tool — render-once, two callsites — so the shape stays consistent
 * whether the worker is given context at spawn or pulls it later.
 *
 * <p>OR-untagged strength filtering: messages without any
 * {@code STRENGTH:*} tag pass every minimum (because prak may not
 * have evaluated them yet). In mature sessions where prak has done
 * its work, the filter actually filters; in young sessions everything
 * shows through.
 */
@Component
@RequiredArgsConstructor
public class ParentContextRenderer {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.ROOT)
                    .withZone(java.time.ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.ROOT)
                    .withZone(java.time.ZoneId.systemDefault());

    /** Per-message body cap before inline truncation marker. */
    private static final int CONTENT_TRIM = 4_000;

    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;
    private final ThinkProcessService thinkProcessService;

    /**
     * Renders the parent-context block. Returns {@code null} when
     * the level is {@link InheritLevel.None} or there is nothing to
     * include (no summary AND no matching messages).
     *
     * @param parentProcessId Mongo id of the spawning ("parent") process
     * @param tenantId        tenant scope for the lookup
     * @param sessionId       session id of the parent
     * @param level           which slice of history to include
     * @param maxChars        hard cap on the rendered output; older
     *                        material is dropped first with a marker.
     *                        Pass &lt;=0 to disable the cap.
     */
    public @Nullable String render(
            String parentProcessId,
            String tenantId,
            String sessionId,
            InheritLevel level,
            int maxChars) {
        if (level instanceof InheritLevel.None) return null;
        if (parentProcessId == null || parentProcessId.isBlank()) return null;
        if (tenantId == null || sessionId == null) return null;

        @Nullable String summary = loadActiveSummary(tenantId, parentProcessId);
        List<ChatMessageDocument> active = chatMessageService
                .activeHistory(tenantId, sessionId, parentProcessId);
        List<ChatMessageDocument> filtered = filter(active, level);

        if ((summary == null || summary.isBlank()) && filtered.isEmpty()) {
            return null;
        }

        ThinkProcessDocument parent = thinkProcessService.findById(parentProcessId).orElse(null);
        String parentName = parent == null ? parentProcessId : parent.getName();
        String parentEngine = parent == null ? "?" : parent.getThinkEngine();

        StringBuilder sb = new StringBuilder();
        sb.append("## Parent context (from `").append(parentName)
                .append("`, engine=").append(parentEngine)
                .append(", level=").append(describe(level)).append(")\n\n");

        if (summary != null && !summary.isBlank()
                && !(level instanceof InheritLevel.Last)) {
            sb.append("### Earlier conversation (compacted summary)\n\n");
            sb.append(summary.trim()).append("\n\n");
        }

        if (!filtered.isEmpty()) {
            sb.append("### Recent conversation (active history)\n\n");
            renderMessages(sb, filtered);
        }

        sb.append("---\n");
        sb.append("Need more parent context (older turns, raw archive, ")
                .append("other strength bands)? Call:\n")
                .append("  process_history_text(name=\"").append(parentName)
                .append("\", includeArchived=true)\n");

        return applyBudget(sb.toString(), maxChars);
    }

    // ─────────────── filter / load helpers ───────────────

    private @Nullable String loadActiveSummary(String tenantId, String processId) {
        List<MemoryDocument> summaries = memoryService.activeByProcessAndKind(
                tenantId, processId, MemoryKind.ARCHIVED_CHAT);
        if (summaries.isEmpty()) return null;
        // activeByProcessAndKind returns ordered by createdAt; the
        // newest active summary is what mirrors the parent's own
        // prompt. Older active entries shouldn't happen (compaction
        // supersedes prior summaries) but defensively pick the last.
        return summaries.get(summaries.size() - 1).getContent();
    }

    private List<ChatMessageDocument> filter(
            List<ChatMessageDocument> msgs, InheritLevel level) {
        if (msgs == null || msgs.isEmpty()) return List.of();
        if (level instanceof InheritLevel.SummaryOnly) return List.of();
        if (level instanceof InheritLevel.All) return msgs;
        if (level instanceof InheritLevel.Last last) {
            int from = Math.max(0, msgs.size() - last.n());
            return msgs.subList(from, msgs.size());
        }
        if (level instanceof InheritLevel.ByStrength bs) {
            List<ChatMessageDocument> out = new ArrayList<>(msgs.size());
            for (ChatMessageDocument m : msgs) {
                if (passesStrength(m, bs.minStrength())) out.add(m);
            }
            return out;
        }
        // None handled at top of render(); defensive default
        return List.of();
    }

    /**
     * OR-untagged: messages with NO {@code STRENGTH:*} tag pass every
     * filter (they haven't been evaluated yet, can't be safely
     * dropped). Tagged messages must meet the minimum.
     */
    private static boolean passesStrength(ChatMessageDocument m, SpanStrength min) {
        Set<String> tags = m.getTags();
        if (tags == null || tags.isEmpty()) return true;
        SpanStrength found = null;
        for (String t : tags) {
            SpanStrength s = SpanStrength.fromTag(t);
            if (s != null) {
                found = s;
                break;
            }
        }
        if (found == null) return true;
        return found.ordinal() >= min.ordinal();
    }

    // ─────────────── rendering ───────────────

    private static void renderMessages(StringBuilder sb, List<ChatMessageDocument> msgs) {
        for (ChatMessageDocument m : msgs) {
            sb.append('[');
            sb.append(m.getCreatedAt() != null
                    ? STAMP.format(m.getCreatedAt()) : "--:--:--");
            sb.append("] ");
            sb.append(m.getRole() == null ? ChatRole.USER.name() : m.getRole().name());
            sb.append(":\n");
            String content = m.getContent();
            if (content != null && !content.isBlank()) {
                String trimmed = content.length() > CONTENT_TRIM
                        ? content.substring(0, CONTENT_TRIM)
                                + "\n[… message truncated, "
                                + (content.length() - CONTENT_TRIM) + " more chars …]"
                        : content;
                for (String line : trimmed.split("\n", -1)) {
                    sb.append("  ").append(line).append('\n');
                }
            } else {
                sb.append("  (empty)\n");
            }
            if (m.getTags() != null && !m.getTags().isEmpty()) {
                sb.append("  ↳ tags: ").append(String.join(", ", m.getTags())).append('\n');
            }
            sb.append('\n');
        }
    }

    private static String applyBudget(String full, int maxChars) {
        if (maxChars <= 0 || full.length() <= maxChars) return full;
        int dropped = full.length() - maxChars;
        String marker = "[… " + dropped
                + " chars of older context truncated; full access via "
                + "process_history_text …]\n\n";
        // Drop from the front (oldest material is at the top of the
        // active-history section). Keep the header line so the worker
        // still sees the context-source label.
        int newlineAfterHeader = full.indexOf("\n\n");
        if (newlineAfterHeader < 0) {
            // No header separator — just truncate from the start.
            return marker + full.substring(dropped);
        }
        String header = full.substring(0, newlineAfterHeader + 2);
        String body = full.substring(newlineAfterHeader + 2);
        if (body.length() <= maxChars - header.length() - marker.length()) {
            return header + marker + body;
        }
        int bodyDrop = body.length() - (maxChars - header.length() - marker.length());
        return header + marker + body.substring(Math.max(0, bodyDrop));
    }

    private static String describe(InheritLevel level) {
        return switch (level) {
            case InheritLevel.None ignored -> "none";
            case InheritLevel.SummaryOnly ignored -> "summary";
            case InheritLevel.All ignored -> "all";
            case InheritLevel.ByStrength bs -> bs.minStrength().name().toLowerCase(Locale.ROOT);
            case InheritLevel.Last last -> "last:" + last.n();
        };
    }
}
