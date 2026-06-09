package de.mhus.vance.brain.fook;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * CRUD + similarity search over {@code fook-ticket} documents. The
 * storage location is fixed: the {@code _vance} system tenant's
 * {@code _tenant} project, under path prefix
 * {@code _vance/fook/tickets/<uuid>.yaml}. Tickets are global by
 * design — every reporter's submission lands in the same pool so
 * Lunkwill can aggregate across tenants.
 *
 * <p>This service is rein internal (Java only) — no LLM tool
 * exposure. The Fook triage LLM only sees pre-loaded
 * {@link TicketCandidate}s rendered into its prompt; all writes
 * happen in {@code FookService} after the LLM returns a triage
 * decision.
 *
 * <p>Similarity search is intentionally simple in v1: token-overlap
 * (Jaccard on lower-cased word-tokens) against title + description.
 * MongoDB has no text index on {@link DocumentDocument} so we pull
 * all {@code fook-ticket}s and score in-memory; that's fine while
 * the pool is small (under a few thousand). When the v1 limit
 * starts hurting triage quality, swap to embedding-based recall.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FookTicketService {

    static final String TENANT_ID = TenantService.SYSTEM_TENANT;
    static final String PROJECT_ID = HomeBootstrapService.TENANT_PROJECT_NAME;
    static final String PATH_PREFIX = "_vance/fook/tickets/";
    static final String DOC_KIND = "fook-ticket";
    static final String STATUS_NEW = "new";
    static final String TRIAGED_BY = "fook";

    /** Cap on tickets pulled for similarity scoring — protects the
     *  triage path from O(n) growth as the pool fills. Tickets
     *  beyond this cap don't surface as candidates; that's the cue
     *  to switch the search to a proper index. */
    static final int MAX_CANDIDATES_SCAN = 500;

    /** Body-field name for the human-readable description (literal
     *  string in the YAML). */
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_TRIAGE_NOTE = "triageNote";
    private static final String FIELD_CONTEXT = "context";
    private static final String FIELD_RELATIONS = "relations";

    private static final Pattern TOKENIZE = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private final DocumentService documentService;

    // ─── Search ─────────────────────────────────────────────────────

    /**
     * Rank existing tickets by token-overlap against the supplied
     * report text. Returns at most {@code limit} entries,
     * highest-score first. Empty result if no tickets exist or if
     * none score above zero.
     */
    public List<TicketCandidate> searchSimilar(String text, int limit) {
        if (limit <= 0) return List.of();
        Set<String> queryTokens = tokenize(text);
        if (queryTokens.isEmpty()) return List.of();

        Page<DocumentDocument> page = documentService.listByProjectPaged(
                TENANT_ID, PROJECT_ID, 0, MAX_CANDIDATES_SCAN,
                PATH_PREFIX, DOC_KIND);
        if (page.isEmpty()) return List.of();

        List<Scored> scored = new ArrayList<>(page.getNumberOfElements());
        for (DocumentDocument doc : page.getContent()) {
            Optional<ParsedTicket> parsed = parse(doc);
            if (parsed.isEmpty()) continue;
            ParsedTicket pt = parsed.get();
            Set<String> docTokens = tokenize(pt.title + " " + pt.description);
            double score = jaccard(queryTokens, docTokens);
            if (score > 0) {
                scored.add(new Scored(toCandidate(pt), score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream()
                .limit(limit)
                .map(s -> s.candidate)
                .toList();
    }

    // ─── Read ───────────────────────────────────────────────────────

    public Optional<TicketDocument> readTicket(String uuid) {
        Optional<DocumentDocument> doc = documentService.findByPath(
                TENANT_ID, PROJECT_ID, pathFor(uuid));
        return doc.flatMap(this::parse).map(this::toDocument);
    }

    // ─── Create ─────────────────────────────────────────────────────

    /**
     * Persist a new ticket. Generates a fresh UUID, sets status to
     * {@code new}, stamps {@code createdAt}/{@code triagedAt}/
     * {@code triagedBy}, and writes the YAML document. Returns the
     * generated UUID so callers can reference it in the inbox item.
     */
    public String createTicket(NewTicketPayload payload) {
        String uuid = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", DOC_KIND);
        meta.put("id", uuid);
        meta.put("title", nonBlank(payload.getTitle(), "(no title)"));
        meta.put("type", nonBlank(payload.getType(), "other"));
        meta.put("severity", nonBlank(payload.getSeverity(), "medium"));
        meta.put("status", STATUS_NEW);
        meta.put("duplicateOf", null);
        putReporter(meta, payload.getReporter());
        meta.put("createdAt", now.toString());
        meta.put("triagedAt", now.toString());
        meta.put("triagedBy", TRIAGED_BY);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$meta", meta);
        root.put(FIELD_DESCRIPTION, nonBlank(payload.getDescription(), ""));
        if (payload.getTriageNote() != null && !payload.getTriageNote().isBlank()) {
            root.put(FIELD_TRIAGE_NOTE, payload.getTriageNote());
        }
        if (payload.getContext() != null) {
            root.put(FIELD_CONTEXT, contextToMap(payload.getContext()));
        }
        Map<String, Object> relations = new LinkedHashMap<>();
        relations.put("rootCauseOf", new ArrayList<>());
        relations.put("relatedTo", normaliseList(payload.getRelatedTickets()));
        root.put(FIELD_RELATIONS, relations);

        documentService.upsertText(
                TENANT_ID, PROJECT_ID, pathFor(uuid),
                payload.getTitle(),
                List.of("fook"),
                dumpYaml(root),
                TRIAGED_BY);
        log.info("Fook: created ticket id='{}' type='{}' severity='{}' " +
                        "reporter={}/{}",
                uuid, meta.get("type"), meta.get("severity"),
                payload.getReporter().getKind(),
                payload.getReporter().getUserId());
        return uuid;
    }

    // ─── Update relations ───────────────────────────────────────────

    /**
     * Read-modify-write merge of relations on an existing ticket.
     * {@code duplicateOf} overwrites the scalar; list patches append
     * (de-duplicated). No optimistic locking — concurrent updates on
     * the same ticket can lose one of the two patches. See the
     * planning doc §1 (race-duplikate akzeptiert).
     */
    public void updateRelations(String uuid, RelationsPatch patch) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                TENANT_ID, PROJECT_ID, pathFor(uuid));
        if (existing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Fook: cannot patch relations — ticket not found: " + uuid);
        }
        DocumentDocument doc = existing.get();
        String body = documentService.readContent(doc);
        Map<String, Object> root = parseRoot(body);
        Map<String, Object> meta = asMap(root.get("$meta"));
        if (meta == null) {
            throw new IllegalStateException(
                    "Fook: ticket '" + uuid + "' is missing $meta block");
        }
        if (patch.getDuplicateOf() != null && !patch.getDuplicateOf().isBlank()) {
            meta.put("duplicateOf", patch.getDuplicateOf());
        }
        Map<String, Object> relations = asMap(root.get(FIELD_RELATIONS));
        if (relations == null) {
            relations = new LinkedHashMap<>();
            relations.put("rootCauseOf", new ArrayList<>());
            relations.put("relatedTo", new ArrayList<>());
            root.put(FIELD_RELATIONS, relations);
        }
        mergeList(relations, "rootCauseOf", patch.getAddRootCauseOf());
        mergeList(relations, "relatedTo", patch.getAddRelatedTo());

        documentService.upsertText(
                TENANT_ID, PROJECT_ID, pathFor(uuid),
                doc.getTitle(),
                doc.getTags(),
                dumpYaml(root),
                TRIAGED_BY);
        log.info("Fook: updated relations on ticket id='{}' duplicateOf='{}' " +
                        "+rootCauseOf={} +relatedTo={}",
                uuid, patch.getDuplicateOf(),
                normaliseList(patch.getAddRootCauseOf()).size(),
                normaliseList(patch.getAddRelatedTo()).size());
    }

    // ─── Helpers ────────────────────────────────────────────────────

    static String pathFor(String uuid) {
        return PATH_PREFIX + uuid + ".yaml";
    }

    private Optional<ParsedTicket> parse(DocumentDocument doc) {
        try {
            String body = documentService.readContent(doc);
            Map<String, Object> root = parseRoot(body);
            return Optional.of(toParsed(root, doc.getPath()));
        } catch (RuntimeException e) {
            log.warn("Fook: skipping malformed ticket document path='{}': {}",
                    doc.getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> parseRoot(String body) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        Object loaded = yaml.load(body);
        if (!(loaded instanceof Map<?, ?> m)) {
            throw new IllegalStateException(
                    "ticket YAML root is not a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) m;
        return root;
    }

    private static String dumpYaml(Map<String, Object> root) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setSplitLines(false);
        opts.setWidth(Integer.MAX_VALUE);
        opts.setPrettyFlow(true);
        return new Yaml(opts).dump(root);
    }

    private static ParsedTicket toParsed(Map<String, Object> root, String path) {
        Map<String, Object> meta = asMap(root.get("$meta"));
        if (meta == null) {
            throw new IllegalStateException("ticket missing $meta: " + path);
        }
        ParsedTicket p = new ParsedTicket();
        p.id = stringOrNull(meta.get("id"));
        p.title = stringOrEmpty(meta.get("title"));
        p.type = stringOrEmpty(meta.get("type"));
        p.severity = stringOrEmpty(meta.get("severity"));
        p.status = stringOrEmpty(meta.get("status"));
        p.duplicateOf = stringOrNull(meta.get("duplicateOf"));
        p.createdAt = parseInstant(meta.get("createdAt"));
        p.triagedAt = parseInstant(meta.get("triagedAt"));
        p.triagedBy = stringOrNull(meta.get("triagedBy"));
        p.reporter = readReporter(meta);
        p.description = stringOrEmpty(root.get(FIELD_DESCRIPTION));
        p.triageNote = stringOrNull(root.get(FIELD_TRIAGE_NOTE));
        p.context = readContext(asMap(root.get(FIELD_CONTEXT)));
        Map<String, Object> relations = asMap(root.get(FIELD_RELATIONS));
        p.rootCauseOf = readStringList(relations, "rootCauseOf");
        p.relatedTo = readStringList(relations, "relatedTo");
        return p;
    }

    private TicketCandidate toCandidate(ParsedTicket p) {
        return TicketCandidate.builder()
                .id(p.id)
                .type(p.type)
                .severity(p.severity)
                .status(p.status)
                .title(p.title)
                .description(p.description)
                .relations(TicketRelations.builder()
                        .duplicateOf(p.duplicateOf)
                        .rootCauseOf(p.rootCauseOf)
                        .relatedTo(p.relatedTo)
                        .build())
                .build();
    }

    private TicketDocument toDocument(ParsedTicket p) {
        return TicketDocument.builder()
                .id(p.id)
                .title(p.title)
                .type(p.type)
                .severity(p.severity)
                .status(p.status)
                .createdAt(p.createdAt)
                .triagedAt(p.triagedAt)
                .triagedBy(p.triagedBy)
                .description(p.description)
                .triageNote(p.triageNote)
                .context(p.context)
                .relations(TicketRelations.builder()
                        .duplicateOf(p.duplicateOf)
                        .rootCauseOf(p.rootCauseOf)
                        .relatedTo(p.relatedTo)
                        .build())
                .reporter(p.reporter)
                .build();
    }

    private static void putReporter(Map<String, Object> meta, TicketReporter r) {
        meta.put("reporterKind", r.getKind().name().toLowerCase());
        meta.put("reporterUserId", r.getUserId());
        meta.put("reporterTenantId", r.getTenantId());
        meta.put("reporterServiceAccount", r.getServiceAccount());
    }

    private static TicketReporter readReporter(Map<String, Object> meta) {
        String kindStr = stringOrNull(meta.get("reporterKind"));
        TicketReporter.Kind kind = TicketReporter.Kind.ENGINE;
        if (kindStr != null) {
            try {
                kind = TicketReporter.Kind.valueOf(kindStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall back to ENGINE — defensive against legacy data
            }
        }
        return TicketReporter.builder()
                .kind(kind)
                .userId(stringOrNull(meta.get("reporterUserId")))
                .tenantId(stringOrNull(meta.get("reporterTenantId")))
                .serviceAccount(stringOrNull(meta.get("reporterServiceAccount")))
                .build();
    }

    private static Map<String, Object> contextToMap(TicketContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ctx.getProjectId() != null) m.put("projectId", ctx.getProjectId());
        if (ctx.getSessionId() != null) m.put("sessionId", ctx.getSessionId());
        if (ctx.getProcessId() != null) m.put("processId", ctx.getProcessId());
        if (ctx.getRecipe() != null) m.put("recipe", ctx.getRecipe());
        if (ctx.getEngine() != null) m.put("engine", ctx.getEngine());
        return m;
    }

    private static @Nullable TicketContext readContext(@Nullable Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        return TicketContext.builder()
                .projectId(stringOrNull(m.get("projectId")))
                .sessionId(stringOrNull(m.get("sessionId")))
                .processId(stringOrNull(m.get("processId")))
                .recipe(stringOrNull(m.get("recipe")))
                .engine(stringOrNull(m.get("engine")))
                .build();
    }

    private static void mergeList(
            Map<String, Object> relations, String key, List<String> add) {
        if (add == null || add.isEmpty()) return;
        List<String> current = readStringList(relations, key);
        LinkedHashSet<String> merged = new LinkedHashSet<>(current);
        merged.addAll(normaliseList(add));
        relations.put(key, new ArrayList<>(merged));
    }

    private static List<String> readStringList(
            @Nullable Map<String, Object> map, String key) {
        if (map == null) return List.of();
        Object raw = map.get(key);
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s.isBlank() ? null : s;
        return o.toString();
    }

    private static String stringOrEmpty(@Nullable Object o) {
        String s = stringOrNull(o);
        return s == null ? "" : s;
    }

    private static String nonBlank(@Nullable String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static List<String> normaliseList(@Nullable List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private static @Nullable Instant parseInstant(@Nullable Object o) {
        String s = stringOrNull(o);
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        String[] parts = TOKENIZE.split(text.toLowerCase());
        Set<String> tokens = new HashSet<>(parts.length);
        for (String p : parts) {
            if (p.length() >= 3) tokens.add(p);
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        for (String s : small) {
            if (large.contains(s)) intersection++;
        }
        if (intersection == 0) return 0.0;
        int union = a.size() + b.size() - intersection;
        return (double) intersection / union;
    }

    // ── Internal value carrier for the YAML-to-DTO bridge ──────────

    private static class ParsedTicket {
        String id;
        String title;
        String type;
        String severity;
        String status;
        @Nullable String duplicateOf;
        @Nullable Instant createdAt;
        @Nullable Instant triagedAt;
        @Nullable String triagedBy;
        TicketReporter reporter;
        String description;
        @Nullable String triageNote;
        @Nullable TicketContext context;
        List<String> rootCauseOf;
        List<String> relatedTo;
    }

    private record Scored(TicketCandidate candidate, double score) {}
}
