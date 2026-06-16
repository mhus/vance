package de.mhus.vance.brain.hactar;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Single-owner Validation-Surface für Scripts. Hactar ist der
 * Executor und damit Eigentümer der Frage "ist dieses Script
 * gültig?". Slart's {@code JsScriptArchitect} (Phase 2) und Cortex's
 * {@code POST /scripts/validate*}-Endpoints (Phase 5) konsumieren
 * diese API — keine parallelen Implementierungen.
 *
 * <p>Zwei Layer:
 *
 * <ul>
 *   <li>{@link #validate(ValidationRequest)} — Parse + Header-Block
 *       + Tool-Allowlist-Intersection. Lokal, kein LLM-Call, ~ms.
 *       Pflicht für jeden Hactar-Lauf (LOADING-Phase), Slart-
 *       VALIDATING und Cortex-Quick-Validate.</li>
 *   <li>{@link #deepValidate(ValidationRequest)} — Semantischer
 *       LLM-Review via {@code LightLlmService} mit Recipe
 *       {@code script-review}. Teuer; opt-in über Hactar's
 *       {@code validateBeforeRun}-Parameter und Cortex-Deep-Validate-
 *       Button.</li>
 * </ul>
 *
 * <p>Implementierung delegiert intern an die bestehenden Bausteine
 * ({@code JsValidationService}, {@code ScriptHeaderParser}) — diese
 * Schnittstelle ist die <em>einzige</em> öffentliche Validation-API,
 * an der Konsumenten andocken. Die internen Helfer bleiben Brain-
 * intern.
 *
 * <p>Siehe {@code planning/script-architect-executor-split.md} §5.6
 * für die vollständige Begründung des Ownership-Modells.
 */
public interface HactarService {

    /**
     * Minimal-Validation. Schnelle, lokale Prüfung — kein LLM-Call.
     *
     * <ul>
     *   <li>Syntax-Parse (GraalJS {@code Source.build()}, ohne
     *       {@code eval})</li>
     *   <li>JSDoc-Header-Parse via {@code ScriptHeaderParser}</li>
     *   <li>Optional: {@code @requiresTools} ⊆
     *       {@link ValidationRequest#callerAllowedTools()} — wenn
     *       {@link ValidationRequest#callerAllowedTools()} nicht
     *       {@code null} ist.</li>
     * </ul>
     *
     * <p>Liefert {@link ValidationResult#ok()} {@code true} wenn alle
     * Schichten clean sind, sonst eine Liste aggregierter
     * {@link ValidationIssue}s. Pebble-/LLM-Aspekte ignoriert.
     */
    ValidationResult validate(ValidationRequest request);

    /**
     * Deep-Validation mit semantischem LLM-Review. Ruft zuerst
     * {@link #validate(ValidationRequest)} — bei {@code !ok} wird
     * <em>kein</em> LLM-Call ausgeführt (kein Sinn, ein nicht-
     * parsendes Script zu reviewen, und keine LLM-Tokens für
     * offensichtlich kaputte Scripts).
     *
     * <p>Bei sauberem Minimal-Pass: {@code LightLlmService} mit
     * Recipe {@code script-review} (markiert {@code internal: true}).
     * LLM erhält Code + Sprache + Quelle und antwortet mit
     * {@code { ok, issues[] }}. Issues werden in
     * {@link ValidationIssue}s gemappt.
     */
    ValidationResult deepValidate(ValidationRequest request);

    // ──────────────────── Records / Enums ────────────────────

    /**
     * Eingabe für beide Methoden. Pflicht: {@code code},
     * {@code language}, {@code sourceName}, {@code tenantId}.
     * Andere Felder sind optional; v1 unterstützt nur
     * {@code language="js"} — andere Werte führen zu
     * {@link IllegalArgumentException}.
     */
    record ValidationRequest(
            String code,
            String language,
            String sourceName,
            @Nullable Set<String> callerAllowedTools,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {

        public ValidationRequest {
            if (code == null) {
                throw new IllegalArgumentException("code is required");
            }
            if (language == null || language.isBlank()) {
                throw new IllegalArgumentException("language is required");
            }
            if (sourceName == null || sourceName.isBlank()) {
                throw new IllegalArgumentException("sourceName is required");
            }
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId is required");
            }
            callerAllowedTools = callerAllowedTools == null
                    ? null : Set.copyOf(callerAllowedTools);
        }
    }

    /**
     * Validierungs-Ergebnis. {@code ok=true} ⇔ {@code issues} leer
     * (und keine Issue mit Severity ERROR vorhanden).
     */
    record ValidationResult(
            boolean ok,
            List<ValidationIssue> issues,
            Duration duration) {

        public ValidationResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
            duration = duration == null ? Duration.ZERO : duration;
        }

        public static ValidationResult ok(Duration duration) {
            return new ValidationResult(true, List.of(), duration);
        }

        public static ValidationResult fail(
                List<ValidationIssue> issues, Duration duration) {
            return new ValidationResult(false, issues, duration);
        }
    }

    /**
     * Eine Validierungs-Auffälligkeit. {@code line}/{@code column}
     * sind 1-based wenn der Parser eine Quelle-Position liefert,
     * sonst {@code null}.
     */
    record ValidationIssue(
            Severity severity,
            String code,
            String message,
            @Nullable Integer line,
            @Nullable Integer column) {

        public ValidationIssue {
            if (severity == null) severity = Severity.ERROR;
            if (code == null || code.isBlank()) code = "unknown";
            if (message == null) message = "";
        }
    }

    /**
     * Härtegrad eines {@link ValidationIssue}. {@code ERROR} drückt
     * {@code ok} auf {@code false}; {@code WARN}/{@code INFO}
     * bleiben informativ und beeinflussen {@code ok} nicht (sind
     * aber in {@code issues} sichtbar).
     */
    enum Severity {
        ERROR,
        WARN,
        INFO
    }
}
