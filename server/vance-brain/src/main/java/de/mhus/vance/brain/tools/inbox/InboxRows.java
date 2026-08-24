package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one place a thread becomes something a model reads.
 *
 * <p>Exists as a single helper for the reason the Zarniwoop hit rows do: there,
 * three tools shaped their own line and only one hardened the foreign text. A
 * thread's title, body and every contribution are written by other people —
 * and its {@code payload} can hold a whole proposed document
 * ({@code STRUCTURE_EDIT}), so an unguarded thread read would blow the prompt.
 *
 * <p>Two rules that look like details and are not:
 * <ul>
 *   <li><b>Canonical fields last.</b> A payload key named {@code title} must not
 *       overwrite the collapsed title with raw text — exactly the bug found in
 *       the search rows.</li>
 *   <li><b>Never truncate silently.</b> A shortened result with no marker reads
 *       to a model as "that is all there is".</li>
 * </ul>
 */
final class InboxRows {

    /** A list line. Long enough to tell threads apart, short enough for fifty of them. */
    private static final int TITLE_MAX = 160;

    /**
     * The thread body <em>is</em> the question. Cutting it hard would make the
     * thread unanswerable, which is worse than the tokens it costs.
     */
    private static final int BODY_MAX = 4000;

    /** As with the Zarniwoop body: enough to judge, not enough to quote. */
    private static final int MESSAGE_BODY_MAX = 1000;

    /** Above this a payload value is a document, not prompt content. */
    private static final int PAYLOAD_VALUE_MAX = 400;

    private InboxRows() {}

    /**
     * One line for {@code inbox_list}: what the thread is and whether it wants
     * something. No body, no payload — that is what {@code thread_get} is for.
     */
    static Map<String, Object> listRow(
            MaximegalonDocument doc, boolean unreadForOwner, @Nullable Integer messageCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("threadId", doc.getId());
        row.put("type", doc.getType() == null ? null : doc.getType().name());
        row.put("status", doc.getStatus() == null ? null : doc.getStatus().name());
        row.put("criticality",
                doc.getCriticality() == null ? null : doc.getCriticality().name());
        row.put("requiresAction", doc.isRequiresAction());
        row.put("unread", unreadForOwner);
        row.put("assignedToUserId", doc.getAssignedToUserId());
        if (doc.getTags() != null && !doc.getTags().isEmpty()) {
            row.put("tags", List.copyOf(doc.getTags()));
        }
        if (messageCount != null) row.put("messageCount", messageCount);
        if (doc.getCreatedAt() != null) row.put("createdAt", doc.getCreatedAt().toString());
        // Canonical last — see class javadoc.
        row.put("title", clamp(doc.getTitle(), TITLE_MAX));
        return row;
    }

    /**
     * The full thread for {@code thread_get}, with the contributions the caller
     * asked for.
     *
     * <p>Three fields are deliberately absent. {@code effectType} becomes
     * {@link #hasEffect}: the type name of a server effect is, to a model, an
     * invitation to trigger it, and there is no tool that could — so the name is
     * useless and the temptation gratuitous. {@code unreadFor} is a server index
     * and nothing an agent can act on. {@code reactions} decide nothing, create
     * no unread and sort nothing, so in a prompt they are pure cost.
     */
    static Map<String, Object> thread(
            MaximegalonDocument doc,
            List<MaximegalonMessage> page,
            int messageOffset,
            int totalMessages) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", doc.getId());
        out.put("type", doc.getType() == null ? null : doc.getType().name());
        out.put("status", doc.getStatus() == null ? null : doc.getStatus().name());
        out.put("criticality",
                doc.getCriticality() == null ? null : doc.getCriticality().name());
        out.put("requiresAction", doc.isRequiresAction());
        out.put("hasEffect", doc.getEffectType() != null);
        out.put("assignedToUserId", doc.getAssignedToUserId());
        out.put("originatorUserId", doc.getOriginatorUserId());
        if (doc.getTeamId() != null) out.put("teamId", doc.getTeamId());
        if (doc.getParticipants() != null && !doc.getParticipants().isEmpty()) {
            out.put("participants", List.copyOf(doc.getParticipants()));
        }
        if (doc.getTags() != null && !doc.getTags().isEmpty()) {
            out.put("tags", List.copyOf(doc.getTags()));
        }
        if (doc.getCreatedAt() != null) out.put("createdAt", doc.getCreatedAt().toString());
        if (doc.getAnswer() != null) out.put("answer", answer(doc));
        Map<String, Object> payload = payload(doc.getPayload());
        if (payload != null) out.put("payload", payload);

        List<Map<String, Object>> messages = new java.util.ArrayList<>(page.size());
        for (MaximegalonMessage m : page) messages.add(message(m));
        out.put("messageCount", totalMessages);
        out.put("messageOffset", messageOffset);
        out.put("messages", messages);
        int omitted = totalMessages - messageOffset - page.size();
        out.put("omittedMessages", Math.max(0, omitted));

        // Canonical last — see class javadoc.
        out.put("title", clamp(doc.getTitle(), TITLE_MAX));
        out.put("body", clamp(doc.getBody(), BODY_MAX));
        return out;
    }

    private static Map<String, Object> answer(MaximegalonDocument doc) {
        Map<String, Object> a = new LinkedHashMap<>();
        var payload = doc.getAnswer();
        if (payload != null) {
            a.put("outcome", payload.getOutcome() == null ? null : payload.getOutcome().name());
            if (payload.getReason() != null) {
                a.put("reason", clamp(payload.getReason(), MESSAGE_BODY_MAX));
            }
            if (payload.getValue() != null) a.put("value", payload(payload.getValue()));
        }
        if (doc.getResolvedBy() != null) a.put("resolvedBy", doc.getResolvedBy().name());
        if (doc.getResolvedAt() != null) a.put("resolvedAt", doc.getResolvedAt().toString());
        return a;
    }

    private static Map<String, Object> message(MaximegalonMessage m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("messageId", m.getId());
        row.put("authorUserId", m.getAuthorUserId());
        if (m.getCreatedAt() != null) row.put("createdAt", m.getCreatedAt().toString());
        if (m.getParentId() != null) row.put("parentId", m.getParentId());
        row.put("body", clamp(m.getBody(), MESSAGE_BODY_MAX));
        return row;
    }

    /**
     * Keys always, values only while they are prompt-sized. A
     * {@code STRUCTURE_EDIT}'s {@code proposed} is a document: knowing it is
     * there is useful, pasting it is not.
     */
    private static @Nullable Map<String, Object> payload(@Nullable Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Number || value instanceof Boolean || value == null) {
                out.put(e.getKey(), value);
                continue;
            }
            String rendered = value instanceof String s ? s : String.valueOf(value);
            // Replaced, not truncated. 400 characters of a proposed document are
            // neither usable (you cannot act on a fragment) nor honest (they look
            // like the whole value). The size says "there is a document here".
            out.put(e.getKey(), rendered.length() <= PAYLOAD_VALUE_MAX
                    ? UntrustedContent.collapseWhitespace(rendered)
                    : "…(" + rendered.length() + " chars, read the referenced document)");
        }
        return out;
    }

    /**
     * Collapses whitespace on foreign text and cuts at the last word boundary,
     * marking the cut. Whitespace first: a title full of newlines breaks the
     * shape of every line around it.
     */
    private static @Nullable String clamp(@Nullable String raw, int max) {
        if (raw == null) return null;
        String flat = UntrustedContent.collapseWhitespace(raw);
        if (flat.length() <= max) return flat;
        String cut = flat.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > max / 2) cut = cut.substring(0, lastSpace);
        return cut + "…";
    }
}
