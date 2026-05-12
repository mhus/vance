package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.SessionSearchHitDto;
import de.mhus.vance.api.session.SessionSearchScope;
import de.mhus.vance.api.session.SessionSummaryRichDto;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

/**
 * Two-stage session search.
 *
 * <ul>
 *   <li><b>Stufe 1 — Metadata.</b> Mongo text index on
 *       {@code SessionDocument} (title + tags). Returns sessions
 *       whose own metadata matches.</li>
 *   <li><b>Stufe 2 — Content.</b> Mongo text index on
 *       {@code ChatMessageDocument.content}. Filters the user-owned
 *       session set first, then text-searches messages within it
 *       and attaches a snippet around the match position.</li>
 * </ul>
 *
 * <p>See {@code specification/session-lifecycle.md} §15.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionSearchService {

    /** Centre-aligned snippet length in characters. */
    private static final int SNIPPET_CHARS = 240;

    /** Hard cap on hits per stage. */
    private static final int STAGE_LIMIT_DEFAULT = 50;

    private final SessionService sessionService;
    private final MongoTemplate mongoTemplate;

    public List<SessionSearchHitDto> search(
            String tenantId,
            String userId,
            String query,
            SessionSearchScope scope,
            boolean includeArchived,
            int limit) {
        if (query == null || query.isBlank()) return List.of();
        int cap = limit > 0 ? Math.min(limit, 200) : STAGE_LIMIT_DEFAULT * 2;
        SessionSearchScope effective = scope == null ? SessionSearchScope.BOTH : scope;

        // Resolve the user's session universe once — both stages need it.
        List<SessionDocument> universe = sessionService.listWithFilters(
                tenantId, userId,
                /*projectId*/ null,
                includeArchived
                        ? java.util.EnumSet.of(
                                de.mhus.vance.api.session.SessionStatus.INIT,
                                de.mhus.vance.api.session.SessionStatus.RUNNING,
                                de.mhus.vance.api.session.SessionStatus.IDLE,
                                de.mhus.vance.api.session.SessionStatus.SUSPENDED,
                                de.mhus.vance.api.session.SessionStatus.ARCHIVED)
                        : java.util.EnumSet.of(
                                de.mhus.vance.api.session.SessionStatus.INIT,
                                de.mhus.vance.api.session.SessionStatus.RUNNING,
                                de.mhus.vance.api.session.SessionStatus.IDLE,
                                de.mhus.vance.api.session.SessionStatus.SUSPENDED),
                /*tag*/ null);
        if (universe.isEmpty()) return List.of();
        Map<String, SessionDocument> byId = new HashMap<>();
        for (SessionDocument s : universe) byId.put(s.getSessionId(), s);

        List<SessionSearchHitDto> hits = new ArrayList<>();
        Set<String> seenSessions = new LinkedHashSet<>();

        if (effective == SessionSearchScope.METADATA || effective == SessionSearchScope.BOTH) {
            hits.addAll(searchMetadata(tenantId, query, byId.keySet(), cap));
            for (SessionSearchHitDto h : hits) seenSessions.add(h.getSession().getSessionId());
        }

        if (effective == SessionSearchScope.CONTENT || effective == SessionSearchScope.BOTH) {
            List<SessionSearchHitDto> contentHits = searchContent(
                    tenantId, query, byId, cap);
            for (SessionSearchHitDto h : contentHits) {
                // Deduplicate against metadata hits — a session that
                // matched in metadata is still shown once, with the
                // metadata-marker; chat-hits for the same session
                // would only add noise. Add chat-hits for sessions
                // we have not yet returned.
                if (seenSessions.add(h.getSession().getSessionId())) {
                    hits.add(h);
                }
            }
        }

        return hits;
    }

    private List<SessionSearchHitDto> searchMetadata(
            String tenantId, String query, Set<String> sessionIds, int cap) {
        if (sessionIds.isEmpty()) return List.of();
        TextCriteria tc = TextCriteria.forDefaultLanguage().matching(query);
        Query tq = TextQuery.queryText(tc)
                .sortByScore()
                .addCriteria(Criteria.where("tenantId").is(tenantId)
                        .and("sessionId").in(sessionIds))
                .limit(cap);
        List<SessionDocument> sessions = mongoTemplate.find(tq, SessionDocument.class);
        List<SessionSearchHitDto> out = new ArrayList<>(sessions.size());
        for (SessionDocument s : sessions) {
            out.add(SessionSearchHitDto.builder()
                    .session(SessionListController.toDto(s))
                    .matchedIn(SessionSearchScope.METADATA)
                    .build());
        }
        return out;
    }

    private List<SessionSearchHitDto> searchContent(
            String tenantId,
            String query,
            Map<String, SessionDocument> byId,
            int cap) {
        if (byId.isEmpty()) return List.of();
        TextCriteria tc = TextCriteria.forDefaultLanguage().matching(query);
        Query tq = TextQuery.queryText(tc)
                .sortByScore()
                .addCriteria(Criteria.where("tenantId").is(tenantId)
                        .and("sessionId").in(byId.keySet())
                        .and("archivedInMemoryId").isNull())
                .limit(cap);
        // Score is exposed via projection; we don't actually need the
        // number — sort order is enough.
        List<ChatMessageDocument> messages = mongoTemplate.find(tq, ChatMessageDocument.class);
        if (messages.isEmpty()) {
            // Fall back to a plain regex (case-insensitive) when the
            // text-index returns nothing — useful for short queries
            // (≤ 2 chars) or stemmer mismatches.
            Query fallback = new Query(Criteria.where("tenantId").is(tenantId)
                    .and("sessionId").in(byId.keySet())
                    .and("archivedInMemoryId").isNull()
                    .and("content").regex(java.util.regex.Pattern.quote(query.trim()),
                            "i"))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(cap);
            messages = mongoTemplate.find(fallback, ChatMessageDocument.class);
        }
        // Deduplicate by sessionId — one chat-hit per session is enough
        // for the list view; the user opens the session to see the rest.
        Set<String> seen = new LinkedHashSet<>();
        List<SessionSearchHitDto> out = new ArrayList<>();
        for (ChatMessageDocument m : messages) {
            if (!seen.add(m.getSessionId())) continue;
            SessionDocument owner = byId.get(m.getSessionId());
            if (owner == null) continue;
            out.add(SessionSearchHitDto.builder()
                    .session(SessionListController.toDto(owner))
                    .matchedIn(SessionSearchScope.CONTENT)
                    .snippet(snippet(m.getContent(), query))
                    .matchedRole(m.getRole() == null ? null : m.getRole().name())
                    .matchedMessageId(m.getId())
                    .matchedAt(m.getCreatedAt())
                    .build());
        }
        return out;
    }

    private static String snippet(String content, String query) {
        if (content == null || content.isEmpty()) return "";
        String lc = content.toLowerCase(Locale.ROOT);
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int idx = needle.isEmpty() ? 0 : lc.indexOf(needle);
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - SNIPPET_CHARS / 2);
        int end = Math.min(content.length(), start + SNIPPET_CHARS);
        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("… ");
        sb.append(content, start, end);
        if (end < content.length()) sb.append(" …");
        // Collapse whitespace for a one-paragraph display.
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
