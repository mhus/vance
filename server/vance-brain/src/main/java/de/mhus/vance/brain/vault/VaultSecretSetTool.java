package de.mhus.vance.brain.vault;

import de.mhus.vance.shared.vault.VaultException;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.shared.vault.VaultService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server tool {@code vault_secret_set} — stores a caller-provided value in the
 * bound vault under {@code key}.
 *
 * <p><b>By the time this is called the value has already passed through the model
 * context</b> — gating it buys nothing against that. If a secret must stay hidden
 * from the model, use {@code vault_secret_generate} instead, which never exposes
 * the value. This tool exists for the honest case where the value is already known
 * in-conversation (e.g. the user pasted it).
 *
 * <p>{@code deferred} + non-primary: sensitive, opt-in via {@code describe_tool}
 * or a recipe's {@code allowedToolsAdd}.
 */
@Component
@Slf4j
public class VaultSecretSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "key", Map.of(
                            "type", "string",
                            "description", "Name to store the secret under. Read it back via "
                                    + "{{secret:vault:<key>}}."),
                    "value", Map.of(
                            "type", "string",
                            "description", "The secret value. NOTE: this passes through the "
                                    + "model context — for a value that must stay hidden from "
                                    + "the model, use vault_secret_generate instead.")),
            "required", List.of("key", "value"));

    private final VaultService vaultService;
    private final VaultToolSupport support;

    public VaultSecretSetTool(VaultService vaultService, VaultToolSupport support) {
        this.vaultService = vaultService;
        this.support = support;
    }

    @Override
    public String name() {
        return "vault_secret_set";
    }

    @Override
    public String description() {
        return "Store a secret value in the bound vault under <key>. WARNING: the "
                + "value passes through the model context — by the time you call this "
                + "it is already in the conversation. For a secret that must stay "
                + "hidden from the model, use vault_secret_generate. Requires a bound "
                + "vault and project-scope write.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "store a known secret value into the vault (value is in-conversation)";
    }

    @Override
    public Set<String> labels() {
        return Set.of("vault", "secret");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        VaultScope scope = support.enforceAndScope(ctx);
        String key = stringOrThrow(params, "key");
        String value = stringOrThrow(params, "value");
        try {
            vaultService.writeSecret(scope, key, value);
        } catch (VaultException e) {
            throw new ToolException("vault_secret_set failed: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("ref", VaultToolSupport.reference(key));
        out.put("written", true);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new ToolException("Missing required parameter '" + key + "'");
    }
}
