package de.mhus.vance.brain.tools.settings;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
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
 * Reads one setting through the same cascade consumers use — think-process →
 * project → {@code _tenant} — the read-side twin of {@code setting_set} and
 * the tool counterpart of the script surface's {@code vance.settings.get}.
 * Answers "what value applies here, and which layer carries it", which is
 * what finishing a setup needs: verify the baseUrl just written, or see
 * that an apiKey exists before claiming the operator still has to set it.
 *
 * <p>Secrets follow the type system's contract: {@code PASSWORD} never
 * leaves the server — it renders as {@code "[set]"} (the same mask the
 * admin REST uses) together with the type, so "is the credential set,
 * and where" stays answerable without exposing it. {@code HIDDEN} is
 * readable by design: it exists precisely for secrets that dynamic
 * elements (scripts <em>and</em> agents) have to resolve themselves, so
 * the decrypted value is returned. Plain settings (STRING and the other
 * value types) return verbatim.
 *
 * <p>Authorization: READ on the target project's setting resource — the
 * same {@link Resource.Setting} shape the admin REST checks, with the
 * weaker action because reading a project's own plain settings matches
 * the script surface, which any process can already reach. Explicit
 * {@code _tenant} reads require ADMIN on the tenant, mirroring
 * {@code setting_set}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettingGetTool implements Tool {

    private static final String MASK = "[set]";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "key",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Setting key in dot notation, e.g. "
                                                    + "'ai.provider.coding-proxy.baseUrl'."),
                            "projectId",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Which project's cascade to read. "
                                                    + "Defaults to the current project; '_tenant' "
                                                    + "reads the tenant-wide default layer.")),
            "required", List.of("key"));

    private final SettingService settingService;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "setting_get";
    }

    @Override
    public String description() {
        return "Read one setting through the cascade that applies in a project "
                + "(think-process → project → _tenant) and report which layer "
                + "holds it. PASSWORD settings render as '[set]' — set or not, "
                + "never the value. HIDDEN settings return their decrypted "
                + "value marked confidential: use it for the task at hand "
                + "(e.g. an Authorization header) and never show, quote or "
                + "paste it to the user, in chat or in any output. "
                + "Use after setting_set to verify a write, or to see which "
                + "scope a value is inherited from.";
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
        @Nullable String requestedProject = optionalString(params, "projectId");
        String projectId =
                requestedProject != null && !requestedProject.isBlank() ? requestedProject.trim() : ctx.projectId();
        if (projectId == null || projectId.isBlank()) {
            throw new ToolException("setting_get requires a project scope — pass projectId");
        }

        // READ on the target project; an explicit _tenant read is tenant-wide
        // and mirrors setting_set's ADMIN gate. Same resource shape as the
        // admin REST.
        Resource resource = HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                ? new Resource.Setting(ctx.tenantId(), SettingService.SCOPE_TENANT, ctx.tenantId(), key)
                : new Resource.Setting(ctx.tenantId(), SettingService.SCOPE_PROJECT, projectId, key);
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                resource,
                HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId) ? Action.ADMIN : Action.READ);

        // Walk the cascade in consumption order; the first hit is the value
        // that applies — report which layer it came from so inheritance is
        // visible instead of guessed.
        Optional<Hit> hit = atScope(ctx, SettingService.SCOPE_THINK_PROCESS, ctx.processId(), key)
                .or(() -> atScope(ctx, SettingService.SCOPE_PROJECT, projectId, key))
                .or(() -> atScope(ctx, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", ctx.tenantId());
        out.put("projectId", projectId);
        out.put("key", key);
        if (hit.isEmpty()) {
            out.put("found", false);
            out.put("note", "not set in any layer of the cascade " + "(think-process → project → _tenant)");
            return out;
        }
        SettingDocument doc = hit.get().doc();
        // The type system's threshold: referenceReadable() covers STRING and
        // HIDDEN — the levels dynamic elements (scripts, agents) may see.
        // PASSWORD (and any future level above it) is masked. Spelled as a
        // predicate, not `== PASSWORD`, for the same reason the agent-write
        // rules use it — a constant comparison silently excludes future levels.
        boolean masked = !doc.getType().referenceReadable();
        out.put("found", true);
        out.put("scope", hit.get().scope());
        out.put("type", doc.getType().name());
        if (masked) {
            out.put("value", doc.getValue() == null ? null : MASK);
            out.put("masked", true);
            out.put(
                    "note",
                    doc.getValue() == null
                            ? "encrypted setting exists but carries no value"
                            : "PASSWORD setting — the value never leaves the server; "
                                    + "'[set]' means a value exists (null would be unset).");
        } else {
            // HIDDEN is agent-readable by contract, but it is encrypted at
            // rest — return the decrypted plaintext, never the ciphertext.
            String value = doc.getType().encrypted()
                    ? settingService.getDecryptedPassword(
                            doc.getTenantId(), doc.getReferenceType(), doc.getReferenceId(), key)
                    : doc.getValue();
            out.put("value", value);
            out.put("masked", false);
            if (doc.getType().encrypted()) {
                if (value == null && doc.getValue() != null) {
                    out.put(
                            "note",
                            "decryption of this HIDDEN setting failed — "
                                    + "see the server log; the value is not reported.");
                } else if (value != null) {
                    // Decrypted secret in the result — say so loudly. The
                    // value is returned so the agent can use it for the task
                    // (an exec_run header, a config check), never so it can
                    // be shown to the user.
                    out.put("confidential", true);
                    out.put(
                            "note",
                            "CONFIDENTIAL — this value was decrypted for this call only. "
                                    + "Do not show, quote or paste it to the user, in chat, documents, "
                                    + "logs or any output you produce; never include it in a file you "
                                    + "write. Use it solely inside a tool call that needs it and refer "
                                    + "to it as 'the setting's value' if you must mention it.");
                }
            }
        }
        return out;
    }

    private record Hit(SettingDocument doc, String scope) {}

    private Optional<Hit> atScope(
            ToolInvocationContext ctx, String referenceType, @Nullable String referenceId, String key) {
        if (referenceId == null || referenceId.isBlank()) {
            return Optional.empty();
        }
        return settingService
                .find(ctx.tenantId(), referenceType, referenceId, key)
                .map(doc -> new Hit(doc, scopeLabel(referenceType, referenceId)));
    }

    private static String scopeLabel(String referenceType, String referenceId) {
        if (SettingService.SCOPE_THINK_PROCESS.equals(referenceType)) {
            return "think-process";
        }
        return HomeBootstrapService.TENANT_PROJECT_NAME.equals(referenceId) ? "_tenant" : "project:" + referenceId;
    }

    private static String requireString(Map<String, Object> params, String name) {
        Object raw = params.get(name);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("setting_get: parameter '" + name + "' must be a non-blank string");
        }
        return s;
    }

    private static @Nullable String optionalString(Map<String, Object> params, String name) {
        Object raw = params.get(name);
        return raw instanceof String s ? s : null;
    }
}
