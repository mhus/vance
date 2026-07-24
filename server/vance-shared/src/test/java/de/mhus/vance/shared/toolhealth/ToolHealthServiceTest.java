package de.mhus.vance.shared.toolhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The cooldown-enforcement and auto-clear <em>decision</em> logic of
 * ToolHealthService — the part that decides "is this call blocked?" and "did a
 * success clear the block?". A bug fires a tool despite an active cooldown
 * (rate-limit violation) or keeps a recovered tool wrongly locked.
 *
 * <p>The Mongo-level atomicity (optimistic-retry / composite-criteria
 * findAndModify) is a Testcontainers concern and intentionally out of scope
 * here (per CLAUDE.md, Mongo integration tests are opt-in). These tests mock
 * the repository and pin the in-JVM matching / expiry / flip-once / history-cap
 * logic.
 */
class ToolHealthServiceTest {

    private static final String TENANT = "acme";
    private static final String TOOL = "web_search";
    private static final String SIG = "HTTP_503";

    private ToolHealthRepository repository;
    private ToolHealthService service;
    private final Instant now = Instant.parse("2026-07-24T10:00:00Z");

    @BeforeEach
    void setUp() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        repository = mock(ToolHealthRepository.class);
        service = new ToolHealthService(mongoTemplate, repository);
    }

    // ──────────────── lookupActiveCooldown: block decision ────────────────

    @Test
    void activeMatchingCooldown_isReturned() {
        stubSession(docWithCooldown(cooldown(SIG, "bob", now.plusSeconds(60))));

        assertThat(lookup("bob")).isPresent();
    }

    @Test
    void expiredCooldown_isNotReturned() {
        // nextSpawnAllowedAt == now → not "after now" → the block has lapsed.
        stubSession(docWithCooldown(cooldown(SIG, "bob", now)));

        assertThat(lookup("bob")).isEmpty();
    }

    @Test
    void signatureMismatch_isNotReturned() {
        stubSession(docWithCooldown(cooldown("OTHER_ERR", "bob", now.plusSeconds(60))));

        assertThat(lookup("bob")).isEmpty();
    }

    @Test
    void userSpecificCooldown_doesNotBlockOtherUsers() {
        stubSession(docWithCooldown(cooldown(SIG, "bob", now.plusSeconds(60))));

        assertThat(lookup("alice")).isEmpty();
        assertThat(lookup("bob")).isPresent();
    }

    @Test
    void scopeWideCooldown_blocksOnlyNullUserCaller() {
        // userId == null on the cooldown = scope-wide; matches() ties it to a
        // null-userId caller only.
        stubSession(docWithCooldown(cooldown(SIG, null, now.plusSeconds(60))));

        assertThat(lookup(null)).isPresent();
        assertThat(lookup("bob")).isEmpty();
    }

    @Test
    void noDocument_meansNoCooldown() {
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                TENANT, ToolHealthScope.SESSION, "s-1", TOOL)).thenReturn(Optional.empty());

        assertThat(lookup("bob")).isEmpty();
    }

    // ──────────────── changeStatus: transitions + history cap ────────────────

    @Test
    void markUnavailable_setsDownWithClassificationAndHistory() {
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                TENANT, ToolHealthScope.TENANT, TENANT, TOOL)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant recovery = now.plus(1, ChronoUnit.HOURS);
        ToolHealthDocument saved = service.markUnavailable(
                TENANT, ToolHealthScope.TENANT, TENANT, TOOL,
                ToolHealthClassification.TECHNICALLY_BROKEN, recovery, "503s", "probe");

        assertThat(saved.getStatus()).isEqualTo(ToolHealthStatus.DOWN);
        assertThat(saved.getLastClassification()).isEqualTo(ToolHealthClassification.TECHNICALLY_BROKEN);
        assertThat(saved.getExpectedRecoveryAt()).isEqualTo(recovery);
        assertThat(saved.getHistory()).hasSize(1);
        assertThat(saved.getHistory().get(0).getStatus()).isEqualTo(ToolHealthStatus.DOWN);
    }

    @Test
    void history_isCappedAtMaxEntries_newestFirst() {
        ToolHealthDocument doc = ToolHealthDocument.builder()
                .tenantId(TENANT).scope(ToolHealthScope.TENANT).scopeId(TENANT)
                .toolName(TOOL).status(ToolHealthStatus.OK).build();
        for (int i = 0; i < ToolHealthService.HISTORY_MAX_ENTRIES; i++) {
            doc.getHistory().add(ToolHealthHistoryEntry.builder()
                    .at(now.minusSeconds(i + 1)).status(ToolHealthStatus.OK).by("seed").build());
        }
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                TENANT, ToolHealthScope.TENANT, TENANT, TOOL)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolHealthDocument saved = service.markDegraded(
                TENANT, ToolHealthScope.TENANT, TENANT, TOOL,
                ToolHealthClassification.INTERMITTENT, null, null, "flaky");

        assertThat(saved.getHistory()).hasSize(ToolHealthService.HISTORY_MAX_ENTRIES);
        assertThat(saved.getHistory().get(0).getStatus()).isEqualTo(ToolHealthStatus.DEGRADED);
    }

    // ──────────────── noteSuccessfulCall: auto-clear + flip-once ────────────────

    @Test
    void successfulCall_flipsScopeToOk_andDropsOwnedCooldown() {
        ToolHealthDocument sessionDoc = ToolHealthDocument.builder()
                .tenantId(TENANT).scope(ToolHealthScope.SESSION).scopeId("s-1")
                .toolName(TOOL).status(ToolHealthStatus.DOWN)
                .cooldowns(new ArrayList<>(List.of(cooldown(SIG, "bob", now.plusSeconds(60)))))
                .build();
        stubScope(ToolHealthScope.SESSION, "s-1", sessionDoc);
        stubScope(ToolHealthScope.USER, "bob", null);
        stubScope(ToolHealthScope.PROJECT, "p-1", null);
        stubScope(ToolHealthScope.TENANT, TENANT, null);
        stubScope(ToolHealthScope.GLOBAL, "", null);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.noteSuccessfulCall(TENANT, "s-1", "bob", "p-1", TOOL);

        assertThat(sessionDoc.getStatus()).isEqualTo(ToolHealthStatus.OK);
        assertThat(sessionDoc.getCooldowns()).isEmpty();
    }

    @Test
    void successfulCall_flipsOnlyNarrowestScope_leavesOuterScopeDown() {
        ToolHealthDocument sessionDoc = downDoc(ToolHealthScope.SESSION, "s-1");
        ToolHealthDocument tenantDoc = downDoc(ToolHealthScope.TENANT, TENANT);
        stubScope(ToolHealthScope.SESSION, "s-1", sessionDoc);
        stubScope(ToolHealthScope.USER, "bob", null);
        stubScope(ToolHealthScope.PROJECT, "p-1", null);
        stubScope(ToolHealthScope.TENANT, TENANT, tenantDoc);
        stubScope(ToolHealthScope.GLOBAL, "", null);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.noteSuccessfulCall(TENANT, "s-1", "bob", "p-1", TOOL);

        assertThat(sessionDoc.getStatus()).isEqualTo(ToolHealthStatus.OK);   // narrowest flips
        assertThat(tenantDoc.getStatus()).isEqualTo(ToolHealthStatus.DOWN);  // outer stays down
        // The outer scope was never re-saved (no change) — flip happened once.
        verify(repository, never()).save(tenantDoc);
    }

    // ──────────────── helpers ────────────────

    private Optional<ToolHealthCooldown> lookup(String userId) {
        return service.lookupActiveCooldown(
                TENANT, ToolHealthScope.SESSION, "s-1", TOOL, SIG, userId, now);
    }

    private void stubSession(ToolHealthDocument doc) {
        stubScope(ToolHealthScope.SESSION, "s-1", doc);
    }

    private void stubScope(ToolHealthScope scope, String scopeId, ToolHealthDocument doc) {
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                eq(TENANT), eq(scope), eq(scopeId), eq(TOOL)))
                .thenReturn(Optional.ofNullable(doc));
    }

    private ToolHealthCooldown cooldown(String signature, String userId, Instant nextAllowedAt) {
        return ToolHealthCooldown.builder()
                .errorSignature(signature).userId(userId).nextSpawnAllowedAt(nextAllowedAt).build();
    }

    private ToolHealthDocument docWithCooldown(ToolHealthCooldown c) {
        return ToolHealthDocument.builder()
                .tenantId(TENANT).scope(ToolHealthScope.SESSION).scopeId("s-1")
                .toolName(TOOL).status(ToolHealthStatus.DOWN)
                .cooldowns(new ArrayList<>(List.of(c))).build();
    }

    private ToolHealthDocument downDoc(ToolHealthScope scope, String scopeId) {
        return ToolHealthDocument.builder()
                .tenantId(TENANT).scope(scope).scopeId(scopeId)
                .toolName(TOOL).status(ToolHealthStatus.DOWN).build();
    }
}
