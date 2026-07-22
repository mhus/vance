package de.mhus.vance.brain.fook.upstream;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.brain.fook.FookTicketService;
import de.mhus.vance.brain.fook.TicketDocument;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sender + poll loops for Fook's upstream transport. Two
 * {@code @Scheduled} ticks:
 *
 * <ol>
 *   <li>{@link #sendTick} — pulls
 *       {@code status=new, transportApproval∈{auto,approved}} tickets,
 *       runs them through {@link FookTicketAnonymizer}, hands the
 *       draft to the configured {@link TicketProvider#create}, marks
 *       the ticket {@code transferred} on success, and patches the
 *       initial inbox-item with the upstream URL.</li>
 *   <li>{@link #pollTick} — pulls {@code status=transferred} tickets,
 *       asks the provider for state + comment deltas since each
 *       ticket's last sync, mirrors the state into {@code $meta} and
 *       drops one inbox-item per new comment.</li>
 * </ol>
 *
 * <p>Both ticks early-out when {@code fook.upstream.mode == never} —
 * day-1 default. The {@link TicketProvider} is picked from the
 * {@code fook.upstream.providerType} setting; if no bean with that
 * name is registered, the tick logs and skips.
 *
 * <p>See {@code specification/fook-upstream.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FookUpstreamService {

    static final String SETTING_MODE = "fook.upstream.mode";
    static final String SETTING_PROVIDER_TYPE = "fook.upstream.providerType";
    static final String SETTING_FINGERPRINT = "fook.upstream.instanceFingerprint";
    static final String SETTING_INSTANCE_SECRET = "fook.upstream.instanceSecret";
    static final String SETTING_ANONYMIZE = "fook.upstream.anonymize";
    static final String SETTING_SCRUB_PATTERNS = "fook.upstream.scrub.patterns";
    static final String SETTING_POLL_ENABLED = "fook.upstream.statusPoll.enabled";
    static final String SETTING_EXTRA_LABELS = "fook.upstream.github.extraLabels";

    static final String MODE_NEVER = "never";
    static final String DEFAULT_PROVIDER = "github";

    static final String INBOX_TAG = "fook";
    static final String INBOX_TAG_STATUS = "fook-status";
    static final String INBOX_TAG_COMMENT = "fook-comment";
    static final String ORIGINATOR = "fook";

    private final FookTicketService ticketService;
    private final FookTicketAnonymizer anonymizer;
    private final InboxItemService inboxItemService;
    private final SettingService settingService;
    private final ClusterMasterService masterService;
    private final List<TicketProvider> providers;

    // ─── send tick ──────────────────────────────────────────────────

    /**
     * Walks pending tickets and pushes them upstream. Default
     * 5-minute cadence; override with
     * {@code vance.fook.upstream.sendTick}.
     */
    @Scheduled(fixedDelayString = "${vance.fook.upstream.sendTick:PT5M}")
    public void sendTick() {
        // Multi-pod safety: only the cluster-master pod sends. Other
        // pods run the same Scheduler but skip — otherwise every pod
        // would push the same pending ticket to GitHub and we'd get
        // N duplicates per tick.
        if (!masterService.isLocalPodMaster()) return;
        if (MODE_NEVER.equals(currentMode())) return;
        TicketProvider provider = currentProvider();
        if (provider == null) return;

        List<TicketDocument> pending = ticketService.listPendingTransport();
        if (pending.isEmpty()) return;

        UpstreamConfig cfg = loadConfig();
        log.info("Fook upstream: sender tick — {} pending ticket(s) for provider '{}'",
                pending.size(), provider.name());

        for (TicketDocument ticket : pending) {
            try {
                transferOne(ticket, provider, cfg);
            } catch (ProviderException e) {
                if (e.isRetryable()) {
                    log.info("Fook upstream: transient transfer failure for {} " +
                                    "({}), will retry on next tick",
                            ticket.getId(), e.getMessage());
                } else {
                    log.warn("Fook upstream: permanent transfer failure for {}: {}",
                            ticket.getId(), e.getMessage());
                    ticketService.markTransferFailed(ticket.getId(), e.getMessage());
                    updateInboxOnFailure(ticket, e);
                }
            } catch (RuntimeException e) {
                log.warn("Fook upstream: unexpected error transferring {}: {}",
                        ticket.getId(), e.getMessage());
            }
        }
    }

    private void transferOne(
            TicketDocument ticket,
            TicketProvider provider,
            UpstreamConfig cfg) {
        ProviderTicketDraft draft = anonymizer.buildDraft(
                ticket,
                cfg.instanceSecret(),
                cfg.instanceFingerprint(),
                cfg.anonymize(),
                cfg.scrubPatterns(),
                cfg.extraLabels());

        ProviderTicketRef ref = provider.create(draft);

        ticketService.markTransferred(
                ticket.getId(), provider.name(), ref.getExternalId(), ref.getUrl());

        updateInboxOnTransfer(ticket, ref);
    }

    private void updateInboxOnTransfer(TicketDocument ticket, ProviderTicketRef ref) {
        if (ticket.getInboxItemId() == null) return;
        String body = "Your submission was transferred to the upstream "
                + "ticket system. Track it at " + ref.getUrl();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", "new_ticket");
        payload.put("ticketId", ticket.getId());
        payload.put("status", "transferred");
        payload.put("upstreamProvider", ref.getProvider());
        payload.put("upstreamExternalId", ref.getExternalId());
        payload.put("upstreamUrl", ref.getUrl());
        try {
            inboxItemService.updateContent(
                    inboxTenantId(ticket),
                    ticket.getInboxItemId(),
                    "Ticket transferred",
                    body,
                    payload,
                    ORIGINATOR);
        } catch (RuntimeException e) {
            log.warn("Fook upstream: could not update inbox item {} for ticket {}: {}",
                    ticket.getInboxItemId(), ticket.getId(), e.getMessage());
        }
    }

    private void updateInboxOnFailure(TicketDocument ticket, ProviderException cause) {
        if (ticket.getInboxItemId() == null) return;
        String body = "Forwarding your submission to the upstream ticket "
                + "system failed permanently. Reason: " + cause.getMessage()
                + ". Contact an admin to retry.";
        try {
            inboxItemService.updateContent(
                    inboxTenantId(ticket),
                    ticket.getInboxItemId(),
                    "Ticket transfer failed",
                    body,
                    Map.of("decision", "new_ticket",
                            "ticketId", ticket.getId(),
                            "status", "failed",
                            "error", cause.getClass().getSimpleName()),
                    ORIGINATOR);
        } catch (RuntimeException e) {
            log.warn("Fook upstream: could not update inbox item {} for ticket {} " +
                            "(failure path): {}",
                    ticket.getInboxItemId(), ticket.getId(), e.getMessage());
        }
    }

    // ─── poll tick ──────────────────────────────────────────────────

    /**
     * Mirrors upstream state and new comments back to the local
     * ticket store + reporter inboxes. Default hourly; override
     * with {@code vance.fook.upstream.pollTick}.
     */
    @Scheduled(fixedDelayString = "${vance.fook.upstream.pollTick:PT1H}")
    public void pollTick() {
        // Same multi-pod guard as sendTick — keeps the GH API rate-limit
        // budget unified and prevents N inbox-items per ticket-status-
        // change.
        if (!masterService.isLocalPodMaster()) return;
        if (MODE_NEVER.equals(currentMode())) return;
        if (!pollEnabled()) return;
        TicketProvider provider = currentProvider();
        if (provider == null) return;

        List<TicketDocument> transferred = ticketService.listTransferredForPolling();
        if (transferred.isEmpty()) return;

        // Match the provider — a ticket sent by GitHub once, but
        // configured to GitLab later, must not be polled by GitLab.
        List<TicketDocument> mine = new ArrayList<>();
        List<ProviderTicketRef> refs = new ArrayList<>();
        for (TicketDocument t : transferred) {
            if (!provider.name().equals(t.getUpstreamProvider())) continue;
            mine.add(t);
            refs.add(ProviderTicketRef.builder()
                    .provider(t.getUpstreamProvider())
                    .externalId(t.getUpstreamExternalId())
                    .url(t.getUpstreamUrl())
                    .build());
        }
        if (mine.isEmpty()) return;

        Instant since = mine.stream()
                .map(TicketDocument::getUpstreamLastSyncedAt)
                .filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.naturalOrder())
                .orElse(mine.stream()
                        .map(TicketDocument::getTransferredAt)
                        .filter(java.util.Objects::nonNull)
                        .min(java.util.Comparator.naturalOrder())
                        .orElse(Instant.EPOCH));

        log.info("Fook upstream: poll tick — checking {} transferred ticket(s) since {}",
                mine.size(), since);

        List<ProviderTicketUpdate> updates;
        try {
            updates = provider.pollUpdates(refs, since);
        } catch (ProviderException e) {
            log.info("Fook upstream: poll failed ({}) — will retry next tick",
                    e.getMessage());
            return;
        }

        // Build a quick lookup from externalId to local ticket.
        Map<String, TicketDocument> byExternalId = new LinkedHashMap<>();
        for (TicketDocument t : mine) {
            byExternalId.put(t.getUpstreamExternalId(), t);
        }

        for (ProviderTicketUpdate update : updates) {
            TicketDocument local = byExternalId.get(update.getRef().getExternalId());
            if (local == null) continue;
            applyUpdate(local, update);
        }
    }

    private void applyUpdate(TicketDocument local, ProviderTicketUpdate update) {
        // Mirror state change.
        if (update.getState() != null
                && !update.getState().equals(local.getUpstreamState())) {
            ticketService.markUpstreamState(local.getId(), update.getState());
            postStatusInbox(local, update);
        } else {
            // Even without a state change, keep lastSyncedAt fresh so the
            // next tick's `since` advances.
            ticketService.markUpstreamState(local.getId(),
                    update.getState() == null ? local.getUpstreamState() : update.getState());
        }

        // Fan out new comments as separate FEEDBACK inbox items.
        for (ProviderTicketUpdate.ProviderComment c : update.getNewComments()) {
            postCommentInbox(local, c);
        }
    }

    private void postStatusInbox(TicketDocument local, ProviderTicketUpdate update) {
        if (local.getReporter() == null
                || local.getReporter().getUserId() == null
                || local.getReporter().getTenantId() == null) return;
        InboxItemDocument item = InboxItemDocument.builder()
                .tenantId(local.getReporter().getTenantId())
                .originatorUserId(ORIGINATOR)
                .assignedToUserId(local.getReporter().getUserId())
                .type(InboxItemType.OUTPUT_TEXT)
                .criticality(Criticality.LOW)
                .tags(List.of(INBOX_TAG, INBOX_TAG_STATUS))
                .title("Ticket status: " + update.getState())
                .body("Your ticket at " + local.getUpstreamUrl()
                        + " is now `" + update.getState() + "`.")
                .payload(Map.of(
                        "ticketId", local.getId(),
                        "upstreamProvider", local.getUpstreamProvider(),
                        "upstreamUrl", local.getUpstreamUrl(),
                        "state", update.getState()))
                .requiresAction(false)
                .build();
        inboxItemService.create(item);
    }

    private void postCommentInbox(
            TicketDocument local, ProviderTicketUpdate.ProviderComment c) {
        if (local.getReporter() == null
                || local.getReporter().getUserId() == null
                || local.getReporter().getTenantId() == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", local.getId());
        payload.put("upstreamProvider", local.getUpstreamProvider());
        payload.put("upstreamUrl", local.getUpstreamUrl());
        payload.put("commentExternalId", c.getExternalId());
        payload.put("author", c.getAuthor());

        InboxItemDocument item = InboxItemDocument.builder()
                .tenantId(local.getReporter().getTenantId())
                .originatorUserId(ORIGINATOR)
                .assignedToUserId(local.getReporter().getUserId())
                .type(InboxItemType.FEEDBACK)
                .criticality(Criticality.NORMAL)
                .tags(List.of(INBOX_TAG, INBOX_TAG_COMMENT))
                .title("New comment from " + c.getAuthor())
                .body(c.getBody() == null ? "(empty comment)" : c.getBody())
                .payload(payload)
                .requiresAction(true)
                .build();
        inboxItemService.create(item);
    }

    // ─── settings / providers ───────────────────────────────────────

    /**
     * Read a tenant-scoped setting. The setting-form's {@code tenant}
     * scope resolves to {@code (project, _tenant)} at persistence
     * time; cascade-read picks that up.
     */
    private String readString(String key, String defaultValue) {
        String v = settingService.getStringValueCascade(
                TenantService.SYSTEM_TENANT, null, null, key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private String currentMode() {
        return readString(SETTING_MODE, MODE_NEVER);
    }

    private boolean pollEnabled() {
        return settingService.getBooleanValueCascade(
                TenantService.SYSTEM_TENANT, null, null, SETTING_POLL_ENABLED, true);
    }

    private @Nullable TicketProvider currentProvider() {
        String type = readString(SETTING_PROVIDER_TYPE, DEFAULT_PROVIDER);
        return providers.stream()
                .filter(p -> p.name().equals(type))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Fook upstream: no provider bean registered for type '{}' — " +
                            "skipping tick", type);
                    return null;
                });
    }

    /** Reads the per-call upstream configuration. Generates and
     *  persists the instance-fingerprint + identity-secret on first
     *  use so the upstream side sees stable correlation across
     *  brain restarts. */
    private UpstreamConfig loadConfig() {
        String fingerprint = ensureSetting(SETTING_FINGERPRINT,
                () -> "vance-" + UUID.randomUUID().toString().substring(0, 8),
                "Opaque identifier for this brain instance, sent to the upstream "
                        + "ticket system so it can group submissions per source.");
        String secret = ensureSetting(SETTING_INSTANCE_SECRET,
                () -> UUID.randomUUID().toString(),
                "Salt for reporter-identity hashing — keep stable so upstream "
                        + "can correlate same-reporter submissions across time.");
        boolean anonymize = settingService.getBooleanValueCascade(
                TenantService.SYSTEM_TENANT, null, null, SETTING_ANONYMIZE, true);
        String patternsCsv = readString(SETTING_SCRUB_PATTERNS, "email,ipv4,apiKey,guid");
        String extraLabelsCsv = readString(SETTING_EXTRA_LABELS, "");
        return new UpstreamConfig(
                fingerprint, secret, anonymize,
                splitCsv(patternsCsv), splitCsv(extraLabelsCsv));
    }

    private String ensureSetting(
            String key,
            java.util.function.Supplier<String> generator,
            String description) {
        String current = readString(key, "");
        if (!current.isBlank()) return current;
        String generated = generator.get();
        // Persist where the form-engine writes: SCOPE_PROJECT, refId=_tenant.
        settingService.set(
                TenantService.SYSTEM_TENANT,
                SettingService.SCOPE_PROJECT,
                de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME,
                key, generated, SettingType.STRING, description);
        // Never log the value — SETTING_INSTANCE_SECRET is the salt that
        // protects reporter-identity hashes from reversal (code-review F4).
        log.info("Fook upstream: generated and persisted {}", key);
        return generated;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String inboxTenantId(TicketDocument ticket) {
        // The inbox-item lives in the reporter's tenant, NOT in the
        // system tenant where the ticket itself lives.
        if (ticket.getReporter() != null && ticket.getReporter().getTenantId() != null) {
            return ticket.getReporter().getTenantId();
        }
        return TenantService.SYSTEM_TENANT;
    }

    record UpstreamConfig(
            String instanceFingerprint,
            String instanceSecret,
            boolean anonymize,
            List<String> scrubPatterns,
            List<String> extraLabels) {}
}
