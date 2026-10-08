package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionItemDto;
import de.mhus.vance.api.admin.AdminActionRunResultDto;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code model-check} — a health check over the models the target scope
 * actually uses: every configured {@code ai.alias.*} setting
 * ({@code default:code}, {@code default:analyze}, …), resolved the way
 * an engine would resolve it, then one minimal real call ("Hallo") per
 * distinct target model. It deliberately does <b>not</b> ping the whole
 * catalog — discovery knows many models nobody uses; the aliases are
 * the configuration that has to work.
 *
 * <p><b>Only distinct models pay for a call:</b> several aliases
 * typically resolve to the same concrete model (code and analyze often
 * share it) — each distinct target is pinged once and every alias
 * pointing at it reports the shared outcome.
 *
 * <p><b>Image models are skipped, not probed:</b> an image alias
 * ({@code default:image}) is Fenchurch territory — a chat probe would
 * always report empty. The catalog knows image kinds, so those rows
 * say "skipped" and stay green instead of burning 20 seconds of
 * empty-response retries.
 *
 * <p><b>The root cause, not the wrapper:</b> the resilient chain wraps
 * provider failures as "All N chat-model chain entries exhausted" —
 * useless to an operator. The ping unwraps the cause chain and reports
 * the deepest message (the gateway's 404 body, the 401, the timeout).
 *
 * <p><b>Bounded parallelism:</b> five concurrent pings on virtual
 * threads, each under a 20-second deadline — a tenant's alias set is
 * small, but one hanging provider must not turn the check into a
 * timeout itself. The check is a finding machine: a failed ping is an
 * ok=false item, not an exception — the summary says how many of the
 * used aliases answered.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelCheckAction implements AdminAction {

    private static final String ALIAS_KEY_PREFIX = "ai.alias.";
    private static final String PING_PROMPT = "Hallo";
    private static final int MAX_CONCURRENT_PINGS = 5;
    private static final Duration PING_DEADLINE = Duration.ofSeconds(20);
    private static final int PING_MAX_TOKENS = 64;

    private final SettingService settingService;
    private final AiModelResolver aiModelResolver;
    private final AiModelService aiModelService;
    private final ModelCatalog modelCatalog;

    @Override
    public String id() {
        return "model-check";
    }

    @Override
    public Map<String, String> title() {
        return Map.of(
                "de", "Konfigurierte Modelle prüfen",
                "en", "Check configured models");
    }

    @Override
    public Map<String, String> description() {
        return Map.of(
                "de",
                        "Sendet einen minimalen echten Call („Hallo“) an jedes Modell, das über "
                                + "einen konfigurierten Alias (ai.alias.*, z.B. default:code) tatsächlich "
                                + "genutzt wird — nicht an den ganzen Katalog. Kosten: ein Mini-Call je "
                                + "Zielmodell; hängende Anbieter laufen nach 20 s ab.",
                "en",
                        "Sends one minimal real call (\"Hello\") to every model actually used "
                                + "through a configured alias (ai.alias.*, e.g. default:code) — not the "
                                + "whole catalog. Cost: one mini call per target model; hanging "
                                + "providers time out after 20 s.");
    }

    @Override
    public AdminActionScope scope() {
        return AdminActionScope.TENANT_AND_PROJECT;
    }

    @Override
    public AdminActionRunResultDto run(AdminActionContext context) {
        // Aliases cascade project → _tenant: a project run checks both
        // layers, the tenant scope only its own — the same layers
        // getStringValueCascade would read.
        Map<String, String> aliasValues = new LinkedHashMap<>();
        collectAliases(context, HomeBootstrapService.TENANT_PROJECT_NAME, aliasValues);
        if (context.projectId() != null && !context.projectId().isBlank()) {
            collectAliases(context, context.projectId(), aliasValues);
        }

        // Resolve every alias to its concrete target. Alias key → spec:
        // "ai.alias.default.chat" → "default:chat" (the first dot
        // separates prefix and rest — the resolver's own grammar).
        Map<String, String> targetByAlias = new LinkedHashMap<>();
        Map<String, String> specByTarget = new LinkedHashMap<>();
        Set<String> imageTargets = new LinkedHashSet<>();
        for (String key : aliasValues.keySet()) {
            String spec = key.substring(ALIAS_KEY_PREFIX.length()).replaceFirst("\\.", ":");
            try {
                AiModelResolver.Resolved resolved =
                        aiModelResolver.resolveOrDefault(spec, context.tenantId(), context.projectId(), null);
                String target = resolved.providerInstance() + ":" + resolved.modelName();
                targetByAlias.put(key, target);
                specByTarget.putIfAbsent(target, spec);
                if (isImageKind(context, resolved)) imageTargets.add(target);
            } catch (RuntimeException e) {
                targetByAlias.put(key, null);
                log.info("ModelCheck: alias '{}' does not resolve: {}", key, e.getMessage());
            }
        }

        // Ping each distinct resolvable chat target once, bounded parallel.
        // Image targets are excluded on purpose — a chat probe against an
        // image model only burns the deadline in empty-response retries.
        Map<String, PingOutcome> outcomes = new ConcurrentHashMap<>();
        runBounded(
                specByTarget.keySet().stream()
                        .filter(t -> !imageTargets.contains(t))
                        .toList(),
                target -> outcomes.put(target, ping(context, specByTarget.get(target))));

        // One row per alias — the configuration the operator asked
        // about. Aliases without a ping share their target's outcome;
        // unresolvable aliases report the resolution failure itself;
        // image aliases are skipped green, not probed.
        List<AdminActionItemDto> items = new ArrayList<>();
        int failed = 0;
        for (Map.Entry<String, String> entry : targetByAlias.entrySet()) {
            String target = entry.getValue();
            AdminActionItemDto.AdminActionItemDtoBuilder row =
                    AdminActionItemDto.builder().key(entry.getKey());
            if (target == null) {
                row.ok(false).detail("alias does not resolve — check the setting value");
                failed++;
            } else if (imageTargets.contains(target)) {
                row.ok(true).detail("image model — no chat probe (image generation runs through Fenchurch)");
            } else {
                PingOutcome outcome = outcomes.getOrDefault(target, new PingOutcome(false, "no result", 0));
                row.ok(outcome.ok()).detail(outcome.detail()).durationMs(outcome.durationMs());
                if (!outcome.ok()) failed++;
            }
            items.add(row.build());
        }
        return AdminActionRunResultDto.builder()
                .actionId(id())
                .ok(failed == 0)
                .summary(
                        "%d of %d configured aliases answered (%d distinct chat models pinged, %d image models skipped)"
                                .formatted(
                                        targetByAlias.size() - failed,
                                        targetByAlias.size(),
                                        (int) targetByAlias.values().stream()
                                                .filter(t -> t != null && !imageTargets.contains(t))
                                                .distinct()
                                                .count(),
                                        imageTargets.size()))
                .items(items)
                .build();
    }

    private void collectAliases(AdminActionContext context, String projectId, Map<String, String> out) {
        settingService
                .findAll(context.tenantId(), SettingService.SCOPE_PROJECT, projectId)
                .forEach(doc -> {
                    String key = doc.getKey();
                    if (!key.startsWith(ALIAS_KEY_PREFIX) || out.containsKey(key)) return;
                    String value = settingService.getStringValue(
                            context.tenantId(), SettingService.SCOPE_PROJECT, projectId, key);
                    if (value != null && !value.isBlank()) {
                        out.put(key, value);
                    }
                });
    }

    /**
     * True when the resolved target is a catalog image model — the Fenchurch
     * path. Those never receive a chat probe: the answer would always be empty,
     * and the operator should not see a red row for a working image pipeline.
     */
    private boolean isImageKind(AdminActionContext context, AiModelResolver.Resolved resolved) {
        return modelCatalog
                        .lookup(context.tenantId(), context.projectId(), resolved.provider(), resolved.modelName())
                        .isEmpty()
                && modelCatalog
                        .lookupImage(context.tenantId(), context.projectId(), resolved.provider(), resolved.modelName())
                        .isPresent();
    }

    /**
     * Unwraps the exception chain to the deepest cause. The resilient chat
     * wrapper throws "All N chat-model chain entries exhausted" — the operator
     * needs the answer underneath: the gateway's 404 body, the 401, the
     * timeout. Falls back to the wrapper message when the chain carries none.
     */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? e.getMessage() : message;
    }

    private record PingOutcome(boolean ok, String detail, long durationMs) {}

    /**
     * One minimal call against the alias target. The config is resolved
     * from the alias spec itself — api key, base URL, catalog limits —
     * the same path an engine's chat takes, so the check exercises the
     * alias, not a hand-built spec.
     */
    private PingOutcome ping(AdminActionContext context, String aliasSpec) {
        long start = System.currentTimeMillis();
        try {
            // resolveOne is static by design — config resolution has no
            // state worth injecting.
            AiChatConfig config = ChatBehaviorBuilder.resolveOne(
                    aliasSpec, context.tenantId(), context.projectId(), null, settingService, aiModelResolver);
            AiChatOptions options = AiChatOptions.builder().build();
            options.setTenantId(context.tenantId());
            options.setProjectId(context.projectId());
            options.setMaxTokens(PING_MAX_TOKENS);
            options.setSyncCallDeadline(PING_DEADLINE);
            String answer = aiModelService
                    .createChat(
                            config,
                            options,
                            CallAttribution.ofService(context.tenantId(), context.projectId(), "_adminAction"))
                    .ask(PING_PROMPT);
            long ms = System.currentTimeMillis() - start;
            boolean ok = answer != null && !answer.isBlank();
            return new PingOutcome(
                    ok, ok ? "answered (%d chars, %d ms)".formatted(answer.length(), ms) : "empty answer", ms);
        } catch (RuntimeException e) {
            return new PingOutcome(false, rootMessage(e), System.currentTimeMillis() - start);
        }
    }

    /** Runs {@code task} for each item on virtual threads, at most {@code MAX_CONCURRENT_PINGS} at a time. */
    private void runBounded(Iterable<String> items, java.util.function.Consumer<String> task) {
        Semaphore permits = new Semaphore(MAX_CONCURRENT_PINGS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            items.forEach(item -> executor.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    task.accept(item);
                } finally {
                    permits.release();
                }
            }));
        }
    }
}
