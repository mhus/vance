package de.mhus.vance.brain.fook;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Triage entry-point: queue, scheduler-driven worker, side-effect
 * application, inbox feedback. See {@code planning/fook-service.md}
 * and the {@code _vance/recipes/fook.yaml} recipe.
 *
 * <p><b>Architecture:</b> {@link #submit} appends to a per-pod
 * in-memory {@link ConcurrentLinkedQueue}. A Spring-scheduled
 * {@link #drainQueue} tick polls each entry, calls
 * {@link FookTicketService#searchSimilar} for the top candidates,
 * hands the bundle to {@link LightLlmService#callForJson} with the
 * {@code fook} recipe as config profile, applies the LLM's
 * decision as a server-side side-effect on the ticket store, and
 * writes one inbox item back to the reporter.
 *
 * <p><b>Cross-pod sync:</b> none. Two pods that triage near-
 * simultaneous reports of the same problem can both decide
 * {@code new_ticket} and produce duplicates; Lunkwill is expected
 * to mop those up later. Documented as accepted in the planning
 * doc §1.
 *
 * <p><b>Crash semantics:</b> queue lives only in process memory.
 * A pod restart loses everything still pending. Reporters whose
 * submission vanished get no inbox item — that's an explicit v1
 * trade-off (the alternative is a persistent queue with all the
 * coordination it implies).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FookService {

    /** Recipe name consumed by {@link LightLlmService}. The recipe
     *  itself lives in the document cascade and may be overridden
     *  per tenant — the cascade lookup uses this exact key. */
    static final String RECIPE_NAME = "fook";

    /** Max candidates pulled per triage run. Higher numbers feed
     *  more context to the LLM at higher cost; eight is a sweet
     *  spot per the Discovery analogue. */
    static final int CANDIDATE_LIMIT = 8;

    /** Tag stamped onto every Fook-produced inbox item — UI surfaces
     *  filter by this. */
    static final String INBOX_TAG = "fook";

    /** Originator user id stamped on inbox items so the audit trail
     *  reads "fook wrote this", not "<reporter> wrote this". */
    static final String ORIGINATOR = "fook";

    /** Schema is intentionally permissive — the recipe template
     *  fully specifies the JSON shape it expects, and the parser
     *  in {@link #parseTriageResult} surfaces shape mismatches
     *  as concrete errors. */
    private static final Map<String, Object> TRIAGE_SCHEMA = Map.of("type", "object");

    /** Setting that decides what {@link FookUpstreamService} (see
     *  {@code de.mhus.vance.brain.fook.upstream}) does with each
     *  triaged ticket. Read at triage-time so the ticket can be
     *  stamped with the right {@code transportApproval} value. */
    static final String SETTING_UPSTREAM_MODE = "fook.upstream.mode";
    static final String MODE_NEVER = "never";
    static final String MODE_AUTOMATIC = "automatic";
    static final String MODE_MANUAL = "manual";

    private final FookTicketService ticketService;
    private final LightLlmService lightLlm;
    private final InboxItemService inboxItemService;
    private final SettingService settingService;

    /** FIFO, thread-safe, lock-free. {@link #submit} writes,
     *  {@link #drainQueue} reads. Bounded only by JVM heap — a
     *  pathological flood gets noticed in metrics, not by a
     *  back-pressure error. */
    private final Queue<Submission> queue = new ConcurrentLinkedQueue<>();

    /** Coarse counter — useful for triage debugging and for the
     *  rare case where a submitted item needs to be located in
     *  flight ("how many ahead of me?"). Not load-bearing. */
    private final AtomicInteger inFlight = new AtomicInteger();

    // ─── public API ─────────────────────────────────────────────────

    /**
     * Enqueue a submission for asynchronous triage. Returns the
     * generated submission id immediately — the caller does not
     * wait for the triage LLM.
     */
    public String submit(SubmissionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("submission request is null");
        }
        if (request.getReporter() == null) {
            throw new IllegalArgumentException("submission reporter is null");
        }
        if (request.getText() == null || request.getText().isBlank()) {
            throw new IllegalArgumentException("submission text is null or blank");
        }
        String submissionId = UUID.randomUUID().toString();
        Submission sub = new Submission(submissionId, request, Instant.now());
        queue.add(sub);
        inFlight.incrementAndGet();
        log.info("Fook: queued submissionId={} reporter={}/{}/{} textChars={}",
                submissionId,
                request.getReporter().getKind(),
                request.getReporter().getTenantId(),
                request.getReporter().getUserId(),
                request.getText().length());
        return submissionId;
    }

    /** Approximate count of submissions waiting in the queue plus
     *  the one currently being processed. Diagnostic only. */
    public int inFlight() {
        return inFlight.get();
    }

    // ─── worker ─────────────────────────────────────────────────────

    /**
     * Scheduler-driven drain. Runs every {@code vance.fook.tick}
     * (default 2 s). Pulls everything in the queue this tick and
     * processes sequentially — no concurrent triage on the same
     * pod, by design (race-duplikate akzeptiert, see §1 planning).
     */
    @Scheduled(fixedDelayString = "${vance.fook.tick:PT2S}")
    public void drainQueue() {
        Submission sub;
        while ((sub = queue.poll()) != null) {
            try {
                processSubmission(sub);
            } catch (RuntimeException e) {
                log.warn("Fook: triage failed for submissionId={}: {}",
                        sub.id(), e.toString());
                safeWriteFailureInbox(sub, e);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    // ─── internals ──────────────────────────────────────────────────

    private void processSubmission(Submission sub) {
        SubmissionRequest req = sub.request();

        List<TicketCandidate> candidates = ticketService.searchSimilar(
                req.getText(), CANDIDATE_LIMIT);

        Map<String, Object> pebbleVars = new LinkedHashMap<>();
        pebbleVars.put("text", req.getText());
        pebbleVars.put("candidates", candidatesAsPebbleList(candidates));

        // Triage runs against the SYSTEM tenant `_vance` if it has LLM
        // credentials configured — that gives every report the same
        // model, the same prompt, and the same triage quality
        // regardless of the reporter's tenant. When `_vance` is
        // un-configured (the day-1 default), we fall back to the
        // reporter's tenant so Fook still works without admin setup.
        Map<String, Object> raw = callTriage(pebbleVars, req);

        TriageResult result = parseTriageResult(raw);
        log.info("Fook: triage submissionId={} decision={} target={} reason={}",
                sub.id(), result.getDecision(), result.getTargetTicketId(),
                result.getReason());

        switch (result.getDecision()) {
            case NEW_TICKET -> handleNewTicket(sub, result);
            case MERGE_INTO -> handleMergeInto(sub, result);
            case DISCARD -> handleDiscard(sub, result);
        }
    }

    private void handleNewTicket(Submission sub, TriageResult result) {
        SubmissionRequest req = sub.request();
        String mode = currentUpstreamMode();
        String transportApproval = transportApprovalForMode(mode);

        NewTicketPayload payload = NewTicketPayload.builder()
                .title(firstNonBlank(result.getDerivedTitle(), fallbackTitle(req.getText())))
                .description(bilingualDescription(req.getText(), result.getEnglishTranslation()))
                .type(firstNonBlank(result.getDerivedType(), "other"))
                .severity(firstNonBlank(result.getDerivedSeverity(), "medium"))
                .reporter(req.getReporter())
                .context(req.getContext())
                .triageNote(result.getTriageNote())
                .relatedTickets(result.getRelatedTickets() == null
                        ? List.of() : result.getRelatedTickets())
                .transportApproval(transportApproval)
                .inboxItemId(null)        // filled in after inbox-item is created
                .build();
        String ticketId = ticketService.createTicket(payload);

        String body = initialInboxBody(ticketId, mode, result);
        InboxItemDocument item = writeInboxItem(sub,
                "Ticket created",
                body,
                Map.of("decision", "new_ticket",
                        "ticketId", ticketId,
                        "submissionId", sub.id(),
                        "transportMode", mode));
        // Back-pointer for FookUpstreamService: the same inbox-item is
        // later patched in-place with the upstream URL.
        if (item != null && item.getId() != null) {
            try {
                ticketService.setInboxItemId(ticketId, item.getId());
            } catch (RuntimeException e) {
                log.warn("Fook: could not stamp inboxItemId on ticket {}: {}",
                        ticketId, e.getMessage());
            }
        }
    }

    private String currentUpstreamMode() {
        // Cascade-read: resolves to (tenant=_vance, scope=project,
        // refId=_tenant) — the same place the setting-form writes to
        // when the admin picks "tenant" scope.
        String v = settingService.getStringValueCascade(
                TenantService.SYSTEM_TENANT, null, null, SETTING_UPSTREAM_MODE);
        return (v == null || v.isBlank()) ? MODE_NEVER : v;
    }

    private static String transportApprovalForMode(String mode) {
        return switch (mode == null ? MODE_NEVER : mode.toLowerCase()) {
            case MODE_AUTOMATIC -> FookTicketService.TRANSPORT_AUTO;
            case MODE_MANUAL -> FookTicketService.TRANSPORT_PENDING;
            default -> FookTicketService.TRANSPORT_NONE;
        };
    }

    private static String initialInboxBody(
            String ticketId, String mode, TriageResult result) {
        String reason = result.getReason() == null ? "" : " " + result.getReason();
        return switch (mode == null ? MODE_NEVER : mode.toLowerCase()) {
            case MODE_AUTOMATIC -> "Your submission was opened as ticket `"
                    + ticketId + "`. It is being forwarded to the upstream "
                    + "ticket system; this item updates with the link once "
                    + "the transfer completes." + reason;
            case MODE_MANUAL -> "Your submission was opened as ticket `"
                    + ticketId + "`. It is waiting for an admin to approve "
                    + "forwarding to the upstream ticket system." + reason;
            default -> "Your submission was opened as ticket `"
                    + ticketId + "`. It stays local — upstream forwarding "
                    + "is disabled on this brain." + reason;
        };
    }

    private void handleMergeInto(Submission sub, TriageResult result) {
        String target = result.getTargetTicketId();
        if (target == null || target.isBlank()) {
            throw new IllegalStateException(
                    "merge_into decision without targetTicketId");
        }
        RelationsPatch.RelationsPatchBuilder patchBuilder =
                RelationsPatch.builder()
                        .addRootCauseOf(List.of())
                        .addRelatedTo(List.of());
        String relation = result.getRelation() == null
                ? "duplicateOf" : result.getRelation();
        // The LLM tells us which slot to fill on the TARGET ticket
        // (target's relation to the current submission is the
        // mirrored statement: "this old ticket duplicates this new
        // submission" or "this old ticket is caused by this
        // submission"). For v1 simplicity we treat the LLM's
        // relation string as a description of the new submission's
        // role, applied to the target ticket — duplicateOf points
        // FROM the duplicate TO the canonical, so when the new
        // submission is a duplicate of the existing ticket, we'd
        // ideally create a fresh ticket and point it at the
        // existing one; here we instead skip the fresh ticket and
        // just record relatedTickets on the target. The exact
        // semantics get tightened by Lunkwill — see §10 planning.
        switch (relation) {
            case "duplicateOf" -> patchBuilder = patchBuilder
                    .addRelatedTo(joinIds(sub, result));
            case "rootCauseOf" -> patchBuilder = patchBuilder
                    .addRootCauseOf(joinIds(sub, result));
            case "relatedTo" -> patchBuilder = patchBuilder
                    .addRelatedTo(joinIds(sub, result));
            default -> patchBuilder = patchBuilder
                    .addRelatedTo(joinIds(sub, result));
        }
        ticketService.updateRelations(target, patchBuilder.build());
        writeInboxItem(sub, "Merged into existing ticket",
                "Your submission was folded into ticket `" + target + "`"
                        + " (" + relation + "). "
                        + (result.getReason() == null ? "" : result.getReason()),
                Map.of("decision", "merge_into",
                        "ticketId", target,
                        "submissionId", sub.id()));
    }

    private void handleDiscard(Submission sub, TriageResult result) {
        String reason = result.getReason() == null
                ? "(no reason given)" : result.getReason();
        String category = result.getCategory() == null
                ? "other" : result.getCategory();
        writeInboxItem(sub, "Submission not opened as a ticket",
                "Fook did not open a ticket for your submission. "
                        + "Category: `" + category + "`. " + reason,
                Map.of("decision", "discard",
                        "category", category,
                        "submissionId", sub.id()));
    }

    private void safeWriteFailureInbox(Submission sub, RuntimeException cause) {
        try {
            writeInboxItem(sub, "Submission could not be triaged",
                    "Fook hit an error while triaging your submission. "
                            + "Try again or escalate to an admin. "
                            + "(`" + cause.getClass().getSimpleName() + "`)",
                    Map.of("decision", "failed",
                            "submissionId", sub.id(),
                            "error", cause.getClass().getSimpleName()));
        } catch (RuntimeException ignored) {
            // Inbox write itself failed — already in a degraded
            // path, just log. The submission is lost.
            log.warn("Fook: failure-inbox-write also failed for submissionId={}",
                    sub.id());
        }
    }

    private @Nullable InboxItemDocument writeInboxItem(
            Submission sub,
            String title,
            String body,
            Map<String, Object> payload) {
        TicketReporter reporter = sub.request().getReporter();
        if (reporter.getKind() == TicketReporter.Kind.SERVICE_ACCOUNT) {
            // v1: service-account submissions don't get an inbox
            // item — there's no user behind them.
            log.info("Fook: skipping inbox for service-account submission " +
                            "submissionId={} serviceAccount={}",
                    sub.id(), reporter.getServiceAccount());
            return null;
        }
        if (reporter.getUserId() == null || reporter.getTenantId() == null) {
            log.warn("Fook: cannot write inbox item — reporter missing " +
                            "userId/tenantId, submissionId={} kind={}",
                    sub.id(), reporter.getKind());
            return null;
        }
        TicketContext ctx = sub.request().getContext();
        InboxItemDocument item = InboxItemDocument.builder()
                .tenantId(reporter.getTenantId())
                .originatorUserId(ORIGINATOR)
                .assignedToUserId(reporter.getUserId())
                .originProcessId(ctx == null ? null : ctx.getProcessId())
                .originSessionId(ctx == null ? null : ctx.getSessionId())
                .type(InboxItemType.OUTPUT_TEXT)
                .criticality(Criticality.LOW)
                .tags(List.of(INBOX_TAG))
                .title(title)
                .body(body)
                .payload(new LinkedHashMap<>(payload))
                .requiresAction(false)
                .build();
        return inboxItemService.create(item);
    }

    /**
     * Two-attempt triage call: {@code _vance} first (centralised
     * model = uniform decisions), reporter tenant second (fallback
     * for the un-configured-system default). The fallback only
     * fires on {@link AiModelResolver.UnknownModelException}; other
     * failures (model error, schema-validation exhausted, …) bubble
     * straight up.
     */
    private Map<String, Object> callTriage(
            Map<String, Object> pebbleVars, SubmissionRequest req) {
        try {
            return lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt("Triage this submission.")
                    .pebbleVars(pebbleVars)
                    .schema(TRIAGE_SCHEMA)
                    .tenantId(TenantService.SYSTEM_TENANT)
                    .build());
        } catch (AiModelResolver.UnknownModelException centralMissing) {
            String reporterTenant = req.getReporter().getTenantId();
            if (reporterTenant == null
                    || reporterTenant.isBlank()
                    || TenantService.SYSTEM_TENANT.equals(reporterTenant)) {
                // No usable fallback — bubble up. The submission lands
                // in the failure-inbox path with a clear error.
                throw centralMissing;
            }
            log.info("Fook: system tenant '{}' has no LLM credentials, " +
                            "falling back to reporter tenant '{}' for triage " +
                            "({})",
                    TenantService.SYSTEM_TENANT, reporterTenant,
                    centralMissing.getMessage());
            String reporterProject = req.getContext() == null
                    ? null : req.getContext().getProjectId();
            return lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt("Triage this submission.")
                    .pebbleVars(pebbleVars)
                    .schema(TRIAGE_SCHEMA)
                    .tenantId(reporterTenant)
                    .projectId(reporterProject)
                    .build());
        }
    }

    // ─── pebble adapters ────────────────────────────────────────────

    /**
     * Assemble the final ticket-description body. When the LLM
     * supplied an {@code englishTranslation} (because the original
     * wasn't English), prepend it and keep the original beneath an
     * {@code --- Original:} divider. When the original was already
     * English ({@code englishTranslation} null / blank), return the
     * original verbatim.
     *
     * <p>Upstream maintainers see English first; reporters retain
     * full audit access to what they actually wrote.
     */
    static String bilingualDescription(
            String original, @Nullable String englishTranslation) {
        if (englishTranslation == null || englishTranslation.isBlank()) {
            return original;
        }
        return englishTranslation.trim()
                + "\n\n--- Original:\n\n"
                + original;
    }

    /**
     * Derive a one-line fallback title from the report text. Used
     * only when the LLM omits {@code derivedTitle} on a new_ticket
     * decision — should normally never trigger because the recipe
     * prompt requires the field. Picks the first non-blank line,
     * trimmed and capped.
     */
    static String fallbackTitle(String text) {
        if (text == null) return "(untitled)";
        for (String line : text.split("\\R", 4)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            return t.length() <= 120 ? t : t.substring(0, 117) + "...";
        }
        return "(untitled)";
    }

    static List<Map<String, Object>> candidatesAsPebbleList(
            List<TicketCandidate> candidates) {
        List<Map<String, Object>> out = new ArrayList<>(candidates.size());
        for (TicketCandidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("type", c.getType());
            m.put("severity", c.getSeverity());
            m.put("status", c.getStatus());
            m.put("title", c.getTitle());
            m.put("description", c.getDescription());
            Map<String, Object> rel = new LinkedHashMap<>();
            TicketRelations r = c.getRelations();
            rel.put("duplicateOf", r == null ? null : r.getDuplicateOf());
            rel.put("rootCauseOf",
                    r == null ? List.of() : r.getRootCauseOf());
            rel.put("relatedTo",
                    r == null ? List.of() : r.getRelatedTo());
            m.put("relations", rel);
            out.add(m);
        }
        return out;
    }

    // ─── TriageResult parsing ───────────────────────────────────────

    static TriageResult parseTriageResult(Map<String, Object> raw) {
        String decisionStr = stringOrNull(raw.get("decision"));
        if (decisionStr == null) {
            throw new IllegalArgumentException(
                    "triage response missing 'decision' field");
        }
        TriageResult.Decision decision = switch (decisionStr.toLowerCase()) {
            case "new_ticket" -> TriageResult.Decision.NEW_TICKET;
            case "merge_into" -> TriageResult.Decision.MERGE_INTO;
            case "discard" -> TriageResult.Decision.DISCARD;
            default -> throw new IllegalArgumentException(
                    "triage response has unknown decision: " + decisionStr);
        };
        return TriageResult.builder()
                .decision(decision)
                .derivedType(stringOrNull(raw.get("derivedType")))
                .derivedSeverity(stringOrNull(raw.get("derivedSeverity")))
                .derivedTitle(stringOrNull(raw.get("derivedTitle")))
                .englishTranslation(stringOrNull(raw.get("englishTranslation")))
                .targetTicketId(stringOrNull(raw.get("targetTicketId")))
                .relation(stringOrNull(raw.get("relation")))
                .relatedTickets(stringList(raw.get("relatedTickets")))
                .category(stringOrNull(raw.get("category")))
                .triageNote(stringOrNull(raw.get("triageNote")))
                .reason(stringOrNull(raw.get("reason")))
                .build();
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static List<String> joinIds(Submission sub, TriageResult result) {
        // The triage LLM's relatedTickets list is the canonical set
        // of extra links to attach to the target ticket on a merge.
        // We don't auto-inject sub.id() because submissionIds and
        // ticketIds are different namespaces — Lunkwill traces back
        // through the inbox payload if it needs to.
        return result.getRelatedTickets() == null
                ? List.of() : result.getRelatedTickets();
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s.isBlank() ? null : s;
        return o.toString();
    }

    private static List<String> stringList(@Nullable Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String firstNonBlank(@Nullable String a, String fallback) {
        return (a == null || a.isBlank()) ? fallback : a;
    }

    /** Internal queue entry. Made package-visible so the test can
     *  reach in and verify queue contents without exposing the
     *  collection. */
    record Submission(String id, SubmissionRequest request, Instant queuedAt) {}
}
