package de.mhus.vance.brain.vault;

import de.mhus.vance.shared.vault.VaultException;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.shared.vault.VaultService;
import de.mhus.vance.shared.vault.VaultService.SecretFormat;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server tool {@code vault_secret_generate} — generates a cryptographically
 * random secret server-side, stores it in the vault bound at the scope — or, with
 * no external manager configured, as a HIDDEN setting — under {@code key}, and
 * returns <b>only its reference</b>, never the value. The leak-free way to
 * provision a fresh credential (DB password, API token) and wire it into
 * compose {@code secrets:} / tool templates via {@code {{secret:vault:<key>}}}
 * without the model ever seeing the value.
 *
 * <p>{@code deferred} + non-primary: sensitive, opt-in via {@code tool_description}
 * or a recipe's {@code allowedToolsAdd}.
 */
@Component
@Slf4j
public class VaultSecretGenerateTool implements Tool {

    private static final int DEFAULT_LENGTH = 32;
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 256;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "key", Map.of(
                            "type", "string",
                            "description", "Name to store the generated secret under in the "
                                    + "vault. Read it back later via {{secret:vault:<key>}}."),
                    "format", Map.of(
                            "type", "string",
                            "enum", List.of("alphanumeric", "hex", "uuid"),
                            "description", "Value format. alphanumeric (default) / hex honour "
                                    + "'length'; uuid ignores it."),
                    "length", Map.of(
                            "type", "integer",
                            "description", "Length for alphanumeric/hex (default 32, "
                                    + MIN_LENGTH + "–" + MAX_LENGTH + ").")),
            "required", List.of("key"));

    private final VaultService vaultService;
    private final VaultToolSupport support;

    public VaultSecretGenerateTool(VaultService vaultService, VaultToolSupport support) {
        this.vaultService = vaultService;
        this.support = support;
    }

    @Override
    public String name() {
        return "vault_secret_generate";
    }

    @Override
    public String description() {
        return "Generate a random secret server-side, store it in the vault under "
                + "<key>, and return only its reference (never the value). Use "
                + "to provision fresh credentials the model must not see — then "
                + "reference them with {{secret:vault:<key>}}. Needs project-scope "
                + "write; no external secret manager has to be configured — without "
                + "one the secret is stored as a HIDDEN setting.";
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
        return "provision/rotate a secret in the vault without exposing its value";
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("vault", "secret");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        VaultScope scope = support.enforceAndScope(ctx);
        String key = stringOrThrow(params, "key");
        SecretFormat format = parseFormat(optString(params, "format"));
        int length = parseLength(params.get("length"));
        try {
            vaultService.generateSecret(scope, key, format, length);
        } catch (VaultException e) {
            throw new ToolException("vault_secret_generate failed: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("ref", VaultToolSupport.reference(key));
        out.put("generated", true);
        out.put("format", format.name().toLowerCase());
        return out;
    }

    private static SecretFormat parseFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return SecretFormat.ALPHANUMERIC;
        }
        try {
            return SecretFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException(
                    "Unknown format '" + raw + "' — expected alphanumeric, hex, or uuid");
        }
    }

    private static int parseLength(Object raw) {
        if (raw == null) {
            return DEFAULT_LENGTH;
        }
        int length;
        if (raw instanceof Number n) {
            length = n.intValue();
        } else {
            try {
                length = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new ToolException("'length' must be an integer");
            }
        }
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new ToolException(
                    "'length' must be between " + MIN_LENGTH + " and " + MAX_LENGTH);
        }
        return length;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        throw new ToolException("Missing required parameter '" + key + "'");
    }

    private static String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
