package de.mhus.vance.brain.tools.settings;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.AgentSettingKeyPolicy;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes one project-scoped setting with a plain (non-encrypted) value — the
 * tool twin of {@code PUT /brain/{tenant}/admin/settings/...}. Closes the gap
 * where the creator sets up persistent configuration (a provider instance's
 * {@code baseUrl}, a routing default, an alias) and then has to hand the
 * operator a settings shopping list instead of finishing the job.
 *
 * <p>Agent-write rules, all enforced here because {@link SettingService#setAs}
 * itself carries none (its callers are human surfaces by contract):
 *
 * <ul>
 *   <li><b>ADMIN on the target scope</b> for every write — same resource shape
 *       ({@link Resource.Setting}) the admin REST controller enforces. The
 *       {@code _tenant} project is addressed as tenant scope, matching the
 *       REST wire mapping.</li>
 *   <li><b>W1</b> — an existing PASSWORD or HIDDEN setting is never
 *       overwritten: what an agent may not read back it may not clobber.
 *       Values are always plain, so credential keys stay on the operator's
 *       surfaces (settings editor, setting form, admin REST).</li>
 *   <li><b>W3</b> — keys on {@link AgentSettingKeyPolicy}'s deny list are
 *       refused, with one carve-out: a recipe that sets
 *       {@code params.allowAiProviderSettings: true} grants writes to exactly
 *       {@code ai.provider.<instance>.type} and {@code ai.provider.<instance>
 *       .baseUrl} (never {@code .apiKey} or any other segment — the exemption
 *       is an allow-list, not a prefix lift). The recipe is operator-curated
 *       and the ADMIN check above still applies, so the flag widens reach for
 *       nobody who could not have written the setting by hand.</li>
 * </ul>
 *
 * <p>The value type follows the existing setting when there is one (an INT
 * stays INT, the string value is parsed by the typed accessors); new keys are
 * created as STRING. No {@code settingType} parameter ever enters the schema —
 * the LLM must not choose encrypted types.
 *
 * <p>Deferred by design; the creator recipe promotes it via the
 * {@code @settings} label selector.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettingSetTool implements Tool {

    /** Recipe param that grants the {@code ai.provider.*} carve-out. */
    static final String PARAM_ALLOW_AI_PROVIDER = "allowAiProviderSettings";

    private static final String AI_PROVIDER_PREFIX = "ai.provider.";

    /**
     * The only {@code ai.provider.*} segments the carve-out may write — an
     * allow-list, deliberately not a prefix lift: {@code apiKey} and anything
     * a future Vance adds stay operator territory until this set grows.
     */
    private static final Set<String> AI_PROVIDER_WRITABLE_SEGMENTS = Set.of("type", "baseUrl");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "key",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Setting key in dot notation, kebab-case "
                                                    + "segments (e.g. 'ai.provider.coding-proxy.baseUrl')."),
                            "value",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "New value. Plain values only — this tool "
                                                    + "cannot create or change PASSWORD/HIDDEN settings; "
                                                    + "credentials go through the settings editor or a "
                                                    + "setting form."),
                            "projectId",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Target project. Defaults to the current "
                                                    + "project; '_tenant' addresses the tenant-wide "
                                                    + "default layer.")),
            "required", List.of("key", "value"));

    private final SettingService settingService;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;
    private final AgentSettingKeyPolicy agentKeyPolicy;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "setting_set";
    }

    @Override
    public String description() {
        return "Write one project-scoped setting (or a tenant-wide one via "
                + "projectId '_tenant'). Plain values only: an existing "
                + "PASSWORD/HIDDEN setting is refused, and a new key becomes "
                + "STRING — credentials must be set by a human through the "
                + "settings editor or a setting form. Requires ADMIN on the "
                + "target scope. Deny-listed keys (ai.provider.*, vault.*, "
                + "store.*, kit.*) are refused unless the running recipe opted "
                + "in — and even then only ai.provider.<instance>.type and "
                + ".baseUrl. Typical use: finish a provider-instance setup by "
                + "writing its baseUrl, or set a routing default.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("admin", "settings");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String key = requireString(params, "key");
        String value = requireString(params, "value");
        @Nullable String requestedProject = optionalString(params, "projectId");
        String projectId =
                requestedProject != null && !requestedProject.isBlank() ? requestedProject.trim() : ctx.projectId();
        if (projectId == null || projectId.isBlank()) {
            throw new ToolException("setting_set requires a project scope — pass projectId");
        }

        // ADMIN on the target scope — same resource shape as the admin REST
        // upsert. The _tenant project is addressed as tenant scope, matching
        // the REST wire mapping (wire 'tenant' → storage 'project/_tenant').
        Resource resource = HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                ? new Resource.Setting(ctx.tenantId(), SettingService.SCOPE_TENANT, ctx.tenantId(), key)
                : new Resource.Setting(ctx.tenantId(), SettingService.SCOPE_PROJECT, projectId, key);
        permissionService.enforce(contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()), resource, Action.ADMIN);

        // W1 — what an agent may not read back it may not overwrite.
        Optional<SettingDocument> existing =
                settingService.find(ctx.tenantId(), SettingService.SCOPE_PROJECT, projectId, key);
        if (existing.isPresent() && existing.get().getType().encrypted()) {
            throw new ToolException(
                    "setting '" + key + "' exists as " + existing.get().getType()
                            + " and cannot be written through an agent-reachable path: PASSWORD/HIDDEN "
                            + "settings can neither be read nor written by an agent. A human has to "
                            + "change it through the settings editor or the matching setting form.");
        }

        // W3 — deny-listed keys, with the recipe-granted ai.provider carve-out.
        if (agentKeyPolicy.isDenied(key) && !aiProviderCarveOut(ctx, key)) {
            throw new ToolException("setting '" + key + "' is reserved for operator "
                    + "configuration and cannot be written by an agent"
                    + (key.startsWith(AI_PROVIDER_PREFIX)
                            ? " — only 'type' and 'baseUrl' of a provider instance are grantable " + "(recipe param "
                                    + PARAM_ALLOW_AI_PROVIDER + "), never credentials"
                            : " — a human has to set it through the settings editor or the "
                                    + "matching setting form"));
        }

        // The type follows the existing setting (non-encrypted here); a new
        // key is STRING. Typed accessors parse from the string value.
        SettingType type = existing.map(SettingDocument::getType).orElse(SettingType.STRING);
        settingService.setAs(
                ctx.tenantId(),
                SettingService.SCOPE_PROJECT,
                projectId,
                key,
                value,
                type, /*description*/
                null, /*actor*/
                ctx.userId());
        log.info(
                "setting_set: tenant='{}' project='{}' key='{}' type='{}' by user='{}'",
                ctx.tenantId(),
                projectId,
                key,
                type,
                ctx.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", ctx.tenantId());
        out.put("projectId", projectId);
        out.put("key", key);
        out.put("type", type.name());
        out.put("value", value);
        out.put("created", existing.isEmpty());
        return out;
    }

    /**
     * Whether the running recipe granted the {@code ai.provider.*} carve-out
     * for this exact key. Fail-closed on anything unknown: no process scope,
     * no engine params, a non-boolean flag or a missing/unlisted segment all
     * deny. The recipe is operator-curated (spawn-frozen params), so this is
     * not an LLM-controllable switch.
     */
    private boolean aiProviderCarveOut(ToolInvocationContext ctx, String key) {
        if (!key.startsWith(AI_PROVIDER_PREFIX)) {
            return false;
        }
        String rest = key.substring(AI_PROVIDER_PREFIX.length());
        int dot = rest.lastIndexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            return false; // no instance + segment pair, or a trailing dot
        }
        if (!AI_PROVIDER_WRITABLE_SEGMENTS.contains(rest.substring(dot + 1))) {
            return false; // apiKey and anything unlisted stay operator territory
        }
        if (ctx.processId() == null || ctx.processId().isBlank()) {
            return false;
        }
        Optional<ThinkProcessDocument> process = thinkProcessService.findById(ctx.processId());
        if (process.isEmpty()) {
            return false;
        }
        Map<String, Object> engineParams = process.get().getEngineParams();
        if (engineParams == null) {
            return false;
        }
        return engineParams.get(PARAM_ALLOW_AI_PROVIDER) instanceof Boolean b && b;
    }

    private static String requireString(Map<String, Object> params, String name) {
        Object raw = params.get(name);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("setting_set: parameter '" + name + "' must be a non-blank string");
        }
        return s;
    }

    private static @Nullable String optionalString(Map<String, Object> params, String name) {
        Object raw = params.get(name);
        return raw instanceof String s ? s : null;
    }
}
