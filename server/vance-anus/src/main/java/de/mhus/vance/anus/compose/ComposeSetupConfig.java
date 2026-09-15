package de.mhus.vance.anus.compose;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Applies an agent-supplied YAML config onto a {@link ComposeSetupState} — the
 * non-interactive twin of the wizard menu. Spec:
 * {@code specification/setup-agent-mode.md}.
 *
 * <p>Rules that make a config safe to drive without a human on the other end:
 * <ul>
 *   <li><b>Fail-closed:</b> unknown keys are errors (a typo'd agent would
 *       otherwise silently run with the default), and every problem is
 *       collected before the run aborts — one shot, one complete error
 *       list.</li>
 *   <li><b>Kebab-case keys</b> mirroring the {@code .env} the wizard renders;
 *       the interactive expert-mode gate does not apply here (the {@code .env}
 *       round-trip exposes those keys anyway, and an agent typing
 *       {@code expose-mongo-port: true} knows what it does).</li>
 *   <li><b>Absent means keep:</b> the wizard pre-fills its state from an
 *       existing {@code .env} before the config applies, so a key that is
 *       absent (or YAML {@code null}) leaves the pre-filled value alone. That
 *       is what makes a re-run idempotent instead of silently rotating the
 *       stack's secrets (which would lock the Mongo volume out).</li>
 *   <li><b>Secrets:</b> {@code mongo-password}, {@code encryption-password}
 *       and {@code internal-token} accept the literal {@code generate} to
 *       force a fresh random value. Never put a secret into a config file
 *       that survives the run — pipe the config via stdin ({@code --config -}).</li>
 *   <li><b>Anus login:</b> {@code anus-password} is plaintext here and hashed
 *       on apply; an empty string disables the login gate explicitly, while
 *       absent/{@code null} keeps the pre-filled hash.</li>
 * </ul>
 */
final class ComposeSetupConfig {

    /** Marker value for the generated secrets — forces a fresh random value. */
    static final String GENERATE = "generate";

    private ComposeSetupConfig() {}

    /**
     * Applies every known key, then validates the state as a whole.
     *
     * @throws ConfigValidationException carrying <b>all</b> problems at once —
     *         an agent gets one round-trip to see everything that is wrong,
     *         not one error per run.
     */
    static void applyTo(Map<String, Object> config, ComposeSetupState s, SecretGenerator secrets) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> e : config.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            switch (key) {
                case "image-namespace" -> str(key, value, s::setImageNamespace, problems);
                case "image-tag" -> str(key, value, s::setImageTag, problems);
                case "mongo-user" -> str(key, value, s::setMongoUser, problems);
                case "mongo-database" -> str(key, value, s::setMongoDatabase, problems);
                case "mongo-port" -> intVal(key, value, s::setMongoPort, problems);
                case "mongo-password" -> secret(key, value, s::setMongoPassword, problems);
                case "encryption-password" -> secret(key, value, s::setEncryptionPassword, problems);
                case "internal-token" -> secret(key, value, s::setInternalToken, problems);
                case "anus-password" -> applyAnusPassword(key, value, s, secrets, problems);
                case "language-name" -> str(key, value, s::setLanguageName, problems);
                case "language-code" -> str(key, value, s::setLanguageCode, problems);
                case "fook-enabled" -> bool(key, value, s::setFookEnabled, problems);
                case "redis-enabled" -> bool(key, value, s::setRedisEnabled, problems);
                case "tools-enabled" -> bool(key, value, s::setToolsEnabled, problems);
                case "anus-service-enabled" -> bool(key, value, s::setAnusServiceEnabled, problems);
                case "external-access" -> bool(key, value, s::setExternalAccess, problems);
                case "external-url" -> str(key, value, s::setExternalUrl, problems);
                case "caddy-tls" -> bool(key, value, s::setCaddyTls, problems);
                case "expose-brain-port" -> bool(key, value, s::setExposeBrainPort, problems);
                case "expose-mongo-port" -> bool(key, value, s::setExposeMongoPort, problems);
                case "expose-redis-port" -> bool(key, value, s::setExposeRedisPort, problems);
                case "brain-port" -> intVal(key, value, s::setBrainPort, problems);
                case "face-port" -> intVal(key, value, s::setFacePort, problems);
                case "redis-port" -> intVal(key, value, s::setRedisPort, problems);
                case "mongo-express-port" -> intVal(key, value, s::setMongoExpressPort, problems);
                case "redis-ui-port" -> intVal(key, value, s::setRedisUiPort, problems);
                case "mongo-express-user" -> str(key, value, s::setMongoExpressUser, problems);
                case "mongo-express-password" -> str(key, value, s::setMongoExpressPassword, problems);
                default ->
                    problems.add(key + ": unknown setting (config keys are kebab-case, see setup-agent-mode.md)");
            }
        }
        // Same rule the interactive wizard enforces through its menu flow: the
        // external access mode is unusable without the URL Caddy and the brain
        // callbacks both need. Caught here so a headless run cannot scaffold a
        // half-reachable stack.
        if (s.isExternalAccess() && s.getExternalUrl().isBlank()) {
            problems.add("external-url: required when external-access is true");
        }
        if (!problems.isEmpty()) {
            throw new ConfigValidationException(problems);
        }
    }

    /** Strings: absent / {@code null} = keep the pre-filled value. */
    private static void str(String key, @Nullable Object value, Consumer<String> setter, List<String> problems) {
        if (value == null) {
            return;
        }
        if (value instanceof String v) {
            setter.accept(v);
            return;
        }
        problems.add(key + ": expected a string, got " + typeName(value));
    }

    private static void bool(String key, @Nullable Object value, Consumer<Boolean> setter, List<String> problems) {
        if (value instanceof Boolean b) {
            setter.accept(b);
            return;
        }
        problems.add(key + ": expected true or false, got " + (value == null ? "nothing" : typeName(value)));
    }

    private static void intVal(String key, @Nullable Object value, Consumer<Integer> setter, List<String> problems) {
        if (value instanceof Integer i) {
            setter.accept(i);
            return;
        }
        problems.add(key + ": expected a number, got " + (value == null ? "nothing" : typeName(value)));
    }

    /**
     * Secrets: {@code generate} clears the field so the wizard's
     * {@code ensureSecrets()} mints a fresh value; absent keeps the pre-fill;
     * a literal string is taken verbatim.
     */
    private static void secret(String key, @Nullable Object value, Consumer<String> setter, List<String> problems) {
        if (value == null) {
            return;
        }
        if (value instanceof String v) {
            setter.accept(GENERATE.equalsIgnoreCase(v.strip()) ? "" : v);
            return;
        }
        problems.add(key + ": expected a string or 'generate', got " + typeName(value));
    }

    private static void applyAnusPassword(
            String key, @Nullable Object value, ComposeSetupState s, SecretGenerator secrets, List<String> problems) {
        if (value == null) {
            return; // keep the pre-filled hash
        }
        if (value instanceof String v) {
            // Empty string is an explicit "no login gate", mirroring the
            // interactive wizard's blank-password path.
            s.setAnusPasswordHash(v.isBlank() ? "" : secrets.bcrypt(v));
            return;
        }
        problems.add(key + ": expected a string, got " + typeName(value));
    }

    private static String typeName(Object value) {
        return value.getClass().getSimpleName();
    }

    /** All collected config problems, one per line. */
    static final class ConfigValidationException extends RuntimeException {

        private final transient List<String> problems;

        ConfigValidationException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        List<String> problems() {
            return problems;
        }
    }
}
