package de.mhus.vance.shared.llmusage;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Cost budgets for chat calls: reads the limits a tenant and a project have
 * set, compares them with what the ledger has booked, and says whether a
 * further call may go out.
 *
 * <h2>Every level has to pass</h2>
 *
 * Each scope's limit is measured against that scope's own consumption — the
 * tenant budget against the whole tenant, a project budget against that
 * project — and a call is allowed only while every limit that is set still
 * has room. Deliberately not the settings cascade, where the innermost value
 * wins: there a project could set itself a limit above the tenant's. Here a
 * project can only narrow itself, and the tenant ceiling always holds. That
 * is also why a project admin may set the project budget.
 *
 * <h2>A soft limit</h2>
 *
 * The cost of a chat call is only known after it returns, so nothing can be
 * reserved up front the way Fenchurch reserves an image slot. The check reads
 * what has been booked so far: the call that crosses the limit still
 * completes, and calls running in parallel at that moment can all pass.
 *
 * <h2>What is read how often</h2>
 *
 * The limits come from the settings of two scopes and change rarely, so they
 * sit in a short cache ({@link #SETTINGS_TTL}). The consumption is read only
 * for a scope that has a limit at all, so a tenant without a budget pays one
 * cached settings lookup and nothing else. For a scope with limits it is one
 * aggregation per check, whatever the number of windows: every window is a
 * conditional sum of the same query.
 *
 * <p>Today is always read fresh — a cached consumption would let a whole
 * burst of calls through after the limit was reached, which is the one thing
 * a budget is for. The days before today are closed: their sums are cached
 * per scope and window start ({@link #CLOSED_DAYS_TTL}), so a monthly or
 * total window does not re-sum its whole history on every call. The only
 * thing the cache can miss is a call booked into yesterday's bucket after
 * midnight, and that for at most the TTL.
 */
@Service
@Slf4j
public class LlmBudgetService {

    /**
     * Callers the budget never stops. They are booked as usual — their cost
     * counts — but they always run, and the limit takes effect on the next
     * regular call. Stopping them midway breaks state: a session that can no
     * longer compact its memory runs into its context window, and a half
     * triaged report is worse than a complete one. A property of the caller,
     * not a configuration choice, hence a constant.
     */
    public static final Set<String> UNGATED_CALLERS =
            Set.of(LlmUsageService.CALLER_COMPACTION, LlmUsageService.CALLER_TRIAGE);

    /**
     * How long the limits of a scope are trusted. Short enough that a raised
     * budget takes effect while the admin is still looking at the screen.
     */
    static final Duration SETTINGS_TTL = Duration.ofSeconds(30);

    /**
     * How long the sum of a window's closed days (before today) is trusted.
     * Those days do not change any more — except for a call that started
     * before midnight and books after it, which is a small amount and late
     * by at most this long.
     */
    static final Duration CLOSED_DAYS_TTL = Duration.ofMinutes(5);

    /** Bound on cached scopes; on overflow the map is cleared, entries are cheap to rebuild. */
    static final int MAX_ENTRIES = 10_000;

    private final SettingService settingService;
    private final LlmUsageService usageService;
    private final Clock clock;
    private final Map<String, CachedBudget> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedTotals> closedDays = new ConcurrentHashMap<>();

    @Autowired
    public LlmBudgetService(SettingService settingService, LlmUsageService usageService) {
        this(settingService, usageService, Clock.systemUTC());
    }

    LlmBudgetService(SettingService settingService, LlmUsageService usageService, Clock clock) {
        this.settingService = settingService;
        this.usageService = usageService;
        this.clock = clock;
    }

    /** Whether {@code caller} is exempt from the gate (see {@link #UNGATED_CALLERS}). */
    public static boolean isUngated(String caller) {
        return UNGATED_CALLERS.contains(caller);
    }

    /**
     * May a further priced call be charged to {@code (tenantId, projectId)}?
     * Never throws: a budget that cannot be read does not block — the ledger
     * keeps counting, and a broken settings read must not take chat down.
     */
    public Verdict check(String tenantId, @Nullable String projectId) {
        try {
            List<WindowStatus> windows = evaluate(tenantId, projectId).stream()
                    .flatMap(s -> s.windows().stream())
                    .toList();
            WindowStatus denial = null;
            List<WindowStatus> warnings = new ArrayList<>();
            for (WindowStatus w : windows) {
                if (w.exhausted()) {
                    if (denial == null) {
                        denial = w;
                    }
                } else if (w.warning()) {
                    warnings.add(w);
                }
            }
            return new Verdict(denial, warnings);
        } catch (RuntimeException e) {
            log.warn(
                    "LLM budget check failed tenant='{}' project='{}' — allowing: {}",
                    tenantId,
                    projectId,
                    e.toString());
            return Verdict.ALLOW;
        }
    }

    /**
     * Budget state of every scope that applies to {@code (tenantId, projectId)},
     * project first. A scope without any limit is still listed, with no
     * windows, so a reader can tell "unlimited" from "not looked at".
     */
    public List<ScopeStatus> status(String tenantId, @Nullable String projectId) {
        return evaluate(tenantId, projectId);
    }

    /** Drops the cached limits and closed-day sums — for tests and for an explicit reload. */
    public void invalidate() {
        cache.clear();
        closedDays.clear();
    }

    // ──────────────────── evaluation ────────────────────

    private List<ScopeStatus> evaluate(String tenantId, @Nullable String projectId) {
        LocalDate today = LocalDate.now(clock);
        List<ScopeStatus> out = new ArrayList<>(2);
        if (isProject(projectId)) {
            out.add(evaluateScope(tenantId, BudgetScope.PROJECT, projectId, projectId, today));
        }
        out.add(evaluateScope(tenantId, BudgetScope.TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, null, today));
        return out;
    }

    private ScopeStatus evaluateScope(
            String tenantId, BudgetScope scope, String scopeId, @Nullable String ledgerProjectId, LocalDate today) {
        LlmBudget budget = budgetOf(tenantId, scopeId);
        Map<BudgetWindow, LocalDate> starts = new EnumMap<>(BudgetWindow.class);
        if (budget.hasLimit()) {
            for (BudgetWindow window : BudgetWindow.values()) {
                LocalDate from = budget.windowStart(window, today);
                if (from != null) {
                    starts.put(window, from);
                }
            }
        }
        if (starts.isEmpty()) {
            return new ScopeStatus(scope, scopeId, budget, List.of());
        }
        Consumption consumption = consumption(tenantId, ledgerProjectId, today, starts.values());
        List<WindowStatus> windows = new ArrayList<>(starts.size());
        for (Map.Entry<BudgetWindow, LocalDate> entry : starts.entrySet()) {
            LocalDate from = entry.getValue();
            List<LlmUsageService.CostTotal> totals = new ArrayList<>(consumption.today());
            if (from.isBefore(today)) {
                totals.addAll(consumption.closedByStart().getOrDefault(from, List.of()));
            }
            windows.add(windowStatus(budget, scope, scopeId, entry.getKey(), from, totals));
        }
        return new ScopeStatus(scope, scopeId, budget, windows);
    }

    /**
     * What one ledger slice has booked for each window start, in a single
     * aggregation: today always, plus {@code [from, yesterday]} for every start
     * before today whose closed-day sum is not cached (any more).
     */
    private Consumption consumption(
            String tenantId, @Nullable String ledgerProjectId, LocalDate today, Collection<LocalDate> starts) {
        LlmUsageService.DayRange todayRange = new LlmUsageService.DayRange(today, today);
        List<LlmUsageService.DayRange> query = new ArrayList<>(starts.size() + 1);
        query.add(todayRange);
        Map<LocalDate, List<LlmUsageService.CostTotal>> closedByStart = new HashMap<>();
        long now = System.nanoTime();
        // Monthly and total can start on the same day — one range for both.
        for (LocalDate from : new TreeSet<>(starts)) {
            if (!from.isBefore(today)) {
                continue;
            }
            CachedTotals hit = closedDays.get(closedKey(tenantId, ledgerProjectId, from, today));
            if (hit != null && now - hit.expiresAtNanos() < 0) {
                closedByStart.put(from, hit.totals());
            } else {
                query.add(new LlmUsageService.DayRange(from, today.minusDays(1)));
            }
        }
        Map<LlmUsageService.DayRange, List<LlmUsageService.CostTotal>> read =
                usageService.costTotals(tenantId, ledgerProjectId, query);
        for (LlmUsageService.DayRange range : query) {
            if (range.equals(todayRange)) {
                continue;
            }
            List<LlmUsageService.CostTotal> totals = read.getOrDefault(range, List.of());
            closedByStart.put(range.from(), totals);
            if (closedDays.size() >= MAX_ENTRIES) {
                closedDays.clear();
            }
            closedDays.put(
                    closedKey(tenantId, ledgerProjectId, range.from(), today),
                    new CachedTotals(totals, now + CLOSED_DAYS_TTL.toNanos()));
        }
        return new Consumption(read.getOrDefault(todayRange, List.of()), closedByStart);
    }

    /** {@code today} is part of the key: at midnight yesterday joins the closed days, and the sum is a new one. */
    private static String closedKey(
            String tenantId, @Nullable String ledgerProjectId, LocalDate from, LocalDate today) {
        String slice = ledgerProjectId == null ? "*" : "p:" + ledgerProjectId;
        return tenantId + '\0' + slice + '\0' + from + '\0' + today;
    }

    private static WindowStatus windowStatus(
            LlmBudget budget,
            BudgetScope scope,
            String scopeId,
            BudgetWindow window,
            LocalDate from,
            List<LlmUsageService.CostTotal> totals) {
        long used = 0;
        long unpriced = 0;
        Map<String, Long> uncounted = new HashMap<>();
        for (LlmUsageService.CostTotal t : totals) {
            unpriced += t.unpricedCalls();
            if (budget.currency().equals(t.currency())) {
                used += t.costMicros();
            } else if (t.costMicros() > 0 && !LlmUsageDailyDocument.NONE.equals(t.currency())) {
                uncounted.merge(t.currency(), t.costMicros(), Long::sum);
            }
        }
        return new WindowStatus(
                scope,
                scopeId,
                window,
                from,
                budget.limitOf(window),
                used,
                budget.currency(),
                budget.warnPercent(),
                unpriced,
                Map.copyOf(uncounted));
    }

    private LlmBudget budgetOf(String tenantId, String scopeId) {
        String key = tenantId + '\0' + scopeId;
        long now = System.nanoTime();
        CachedBudget hit = cache.get(key);
        if (hit != null && now - hit.expiresAtNanos() < 0) {
            return hit.budget();
        }
        LlmBudget budget = readBudget(tenantId, scopeId);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new CachedBudget(budget, now + SETTINGS_TTL.toNanos()));
        return budget;
    }

    private LlmBudget readBudget(String tenantId, String scopeId) {
        Map<String, String> raw = new HashMap<>();
        for (SettingDocument doc : settingService.findAll(tenantId, SettingService.SCOPE_PROJECT, scopeId)) {
            if (doc.getKey().startsWith(LlmBudget.PREFIX) && !doc.getType().encrypted() && doc.getValue() != null) {
                raw.put(doc.getKey(), doc.getValue());
            }
        }
        return raw.isEmpty() ? LlmBudget.NONE : LlmBudget.parse(raw);
    }

    private static boolean isProject(@Nullable String projectId) {
        return projectId != null
                && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                && !LlmUsageDailyDocument.NONE.equals(projectId);
    }

    private record CachedBudget(LlmBudget budget, long expiresAtNanos) {}

    private record CachedTotals(List<LlmUsageService.CostTotal> totals, long expiresAtNanos) {}

    /**
     * @param today         booked today, read fresh
     * @param closedByStart booked from a window start until yesterday, per start
     */
    private record Consumption(
            List<LlmUsageService.CostTotal> today, Map<LocalDate, List<LlmUsageService.CostTotal>> closedByStart) {}

    // ──────────────────── results ────────────────────

    /**
     * Outcome of {@link #check}.
     *
     * @param denial   the first exhausted window (project before tenant,
     *                 daily before monthly before total), {@code null} when
     *                 the call may go out
     * @param warnings windows past their warning threshold but not exhausted
     */
    public record Verdict(@Nullable WindowStatus denial, List<WindowStatus> warnings) {

        public static final Verdict ALLOW = new Verdict(null, List.of());

        public boolean allowed() {
            return denial == null;
        }
    }

    /**
     * One scope's budget and its active windows.
     *
     * @param scopeId the settings scope — a project name or {@code _tenant}
     * @param budget  the limits as read from the settings
     * @param windows the windows with a limit that applies today
     */
    public record ScopeStatus(BudgetScope scope, String scopeId, LlmBudget budget, List<WindowStatus> windows) {}

    /**
     * Consumption against one limit.
     *
     * @param from             first day of the window (inclusive, UTC)
     * @param limitMicros      the limit, micro-units of {@code currency}
     * @param usedMicros       cost booked in {@code currency} within the window
     * @param warnPercent      threshold for {@link #warning()}, {@code 0} = off
     * @param unpricedCalls    calls of models without pricing — invisible to the
     *                         amount, listed so the gap is not silent
     * @param uncountedMicros  cost booked in other currencies, by currency — not
     *                         part of {@code usedMicros}, never converted
     */
    public record WindowStatus(
            BudgetScope scope,
            String scopeId,
            BudgetWindow window,
            LocalDate from,
            long limitMicros,
            long usedMicros,
            String currency,
            int warnPercent,
            long unpricedCalls,
            Map<String, Long> uncountedMicros) {

        public boolean exhausted() {
            return limitMicros > 0 && usedMicros >= limitMicros;
        }

        public boolean warning() {
            return warnPercent > 0 && limitMicros > 0 && usedMicros * 100 >= limitMicros * warnPercent;
        }

        /** Used share of the limit in percent, not capped at 100. */
        public double percent() {
            return limitMicros <= 0 ? 0.0 : usedMicros * 100.0 / limitMicros;
        }
    }
}
