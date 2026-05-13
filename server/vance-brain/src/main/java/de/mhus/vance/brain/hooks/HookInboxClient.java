package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbox write channel exposed as {@code inbox.*}. The script supplies
 * a single map; the dispatcher decides who the assignee is — the hook
 * cannot route to an arbitrary user across the tenant.
 *
 * <p>Default routing:
 * <ul>
 *   <li>{@code recipient: "owner"} — the project owner / first member
 *       (resolved by the dispatcher; passed in as
 *       {@link #defaultRecipientUserId}).</li>
 *   <li>{@code recipient: "createdBy"} — the user who created the
 *       hook document (also pre-resolved).</li>
 *   <li>{@code recipient: "<userName>"} — explicit user (no permission
 *       check at this layer; the tenant-isolation block stops cross-
 *       tenant routing because the dispatcher refuses to set
 *       {@code tenantId} from script input).</li>
 * </ul>
 */
public final class HookInboxClient {

    private static final Logger LOG = LoggerFactory.getLogger("vance.hooks.inbox");

    private final InboxItemService inboxService;
    private final String tenantId;
    private final String hookName;
    private final String defaultRecipientUserId;
    private final @Nullable String createdByUserId;

    public HookInboxClient(
            InboxItemService inboxService,
            String tenantId,
            String hookName,
            String defaultRecipientUserId,
            @Nullable String createdByUserId) {
        this.inboxService = inboxService;
        this.tenantId = tenantId;
        this.hookName = hookName;
        this.defaultRecipientUserId = defaultRecipientUserId;
        this.createdByUserId = createdByUserId;
    }

    @HostAccess.Export
    public Map<String, Object> create(Map<String, Object> spec) {
        if (spec == null) {
            throw new HookInboxException("inbox.create requires a spec map");
        }
        String title = stringOrThrow(spec, "title");
        @Nullable String body = stringOrNull(spec, "body");
        InboxItemType type = parseType(spec.get("type"));
        Criticality criticality = parseCriticality(spec.get("criticality"));
        List<String> tags = parseTags(spec.get("tags"));
        String recipient = resolveRecipient(spec.get("recipient"));
        boolean requiresAction = isAsk(type);

        InboxItemDocument item = InboxItemDocument.builder()
                .tenantId(tenantId)
                .originatorUserId("hook:" + hookName)
                .assignedToUserId(recipient)
                .type(type)
                .criticality(criticality)
                .title(title)
                .body(body)
                .tags(tags)
                .requiresAction(requiresAction)
                .payload(new LinkedHashMap<>())
                .build();

        InboxItemDocument saved = inboxService.create(item);
        LOG.debug("hook '{}' created inbox item id={} type={} recipient={}",
                hookName, saved.getId(), type, recipient);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("recipient", recipient);
        return result;
    }

    private static InboxItemType parseType(@Nullable Object raw) {
        if (raw == null) return InboxItemType.OUTPUT_TEXT;
        if (!(raw instanceof String s)) {
            throw new HookInboxException("'type' must be a string");
        }
        try {
            return InboxItemType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new HookInboxException("unknown inbox type '" + s + "'");
        }
    }

    private static Criticality parseCriticality(@Nullable Object raw) {
        if (raw == null) return Criticality.NORMAL;
        if (raw instanceof String s) {
            try {
                return Criticality.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new HookInboxException("unknown criticality '" + s + "'");
            }
        }
        throw new HookInboxException("'criticality' must be a string");
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseTags(@Nullable Object raw) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> l)) {
            throw new HookInboxException("'tags' must be a list of strings");
        }
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o == null) continue;
            out.add(String.valueOf(o));
        }
        return out;
    }

    private String resolveRecipient(@Nullable Object raw) {
        if (raw == null) return defaultRecipientUserId;
        if (!(raw instanceof String s) || s.isBlank()) return defaultRecipientUserId;
        return switch (s.trim()) {
            case "owner" -> defaultRecipientUserId;
            case "createdBy" -> createdByUserId == null ? defaultRecipientUserId : createdByUserId;
            default -> s.trim();
        };
    }

    private static boolean isAsk(InboxItemType type) {
        return switch (type) {
            case APPROVAL, DECISION, FEEDBACK, ORDERING, STRUCTURE_EDIT -> true;
            case OUTPUT_TEXT, OUTPUT_IMAGE, OUTPUT_DOCUMENT -> false;
        };
    }

    private static String stringOrThrow(Map<String, Object> spec, String key) {
        Object v = spec.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new HookInboxException("'" + key + "' is required");
        }
        return s;
    }

    private static @Nullable String stringOrNull(Map<String, Object> spec, String key) {
        Object v = spec.get(key);
        if (!(v instanceof String s) || s.isBlank()) return null;
        return s;
    }

    /** Surfaced to JS as a regular {@code Error}. */
    public static final class HookInboxException extends RuntimeException {
        public HookInboxException(String message) { super(message); }
    }
}
