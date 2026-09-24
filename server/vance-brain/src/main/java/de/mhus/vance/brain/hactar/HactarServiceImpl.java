package de.mhus.vance.brain.hactar;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptHeader;
import de.mhus.vance.brain.script.ScriptHeaderParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Default-Implementierung von {@link HactarService}. Delegiert
 * Parse- und Header-Logik an die bestehenden Bausteine in
 * {@code de.mhus.vance.brain.script}; Deep-Validate ruft
 * {@link LightLlmService} mit Recipe {@code script-review}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HactarServiceImpl implements HactarService {

    /** Name des bundled Light-LLM-Recipes für Deep-Validate. Internal,
     *  nicht spawnbar. */
    static final String SCRIPT_REVIEW_RECIPE = "script-review";

    /** Permissive Top-Level-Schema — die eigentliche Form-Validierung
     *  übernimmt {@link #parseLlmReviewReply}. Genauer hier zu sein
     *  würde nur den Schema-Loop teurer machen ohne semantischen
     *  Mehrwert. */
    private static final Map<String, Object> REVIEW_SCHEMA = Map.of("type", "object");

    private final JsValidationService jsValidationService;
    private final LightLlmService lightLlm;

    @Override
    public ValidationResult validate(ValidationRequest request) {
        long startNs = System.nanoTime();
        requireSupportedLanguage(request);

        List<ValidationIssue> issues = new ArrayList<>();

        // 1. Syntax-Parse (sprachspezifisch).
        switch (request.language()) {
            case "js" -> collectJsParseIssues(request, issues);
            default -> throw new IllegalArgumentException("Unsupported language: " + request.language());
        }

        // 2. Header-Block-Parse — auch wenn der Parse oben fehlschlug
        //    (der Header steht vor jedem Statement und kann unabhängig
        //    geparsed werden).
        ScriptHeader header = collectHeaderIssues(request, issues);

        // 3. Tool-Allowlist-Intersection. Nur wenn der Caller eine
        //    Allow-Liste mitgibt — sonst gibt es keine Constraints zu
        //    prüfen.
        if (request.callerAllowedTools() != null && !header.requiresTools().isEmpty()) {
            for (String required : header.requiresTools()) {
                if (!request.callerAllowedTools().contains(required)) {
                    issues.add(new ValidationIssue(
                            Severity.ERROR,
                            "missing_required_tool",
                            "@requiresTools declares '" + required
                                    + "' but the caller's effective allow-set "
                                    + "does not contain it",
                            null,
                            null));
                }
            }
        }

        // 4. Vance-Host-Surface-Check — deterministisch. Syntax + Header
        //    + Tool-Allowlist können einen unbekannten `vance.*`-Member
        //    nicht sehen; das LLM-Deep-Validate kennt die exakte
        //    Host-Oberfläche nicht zuverlässig. Ohne diesen Check stirbt
        //    der Fehler erst zur Laufzeit (live beobachtet:
        //    `vance.sleep(...)` → TypeError im EXECUTING, obwohl Slart
        //    das Script authored UND deep-validate lief). Der Check
        //    läuft im minimalen Gate — Slart-Authoring (Draft-Validierung
        //    mit Recovery-Runde) und Hactar-LOADING (fail-fast vor dem
        //    ersten Statement) sehen dieselben Issues.
        if ("js".equals(request.language())) {
            collectUnknownVanceMemberIssues(request, issues);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startNs);
        boolean hasError = issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        return hasError ? ValidationResult.fail(issues, duration) : new ValidationResult(true, issues, duration);
    }

    /** First-level {@code vance.*} member names — collected from
     *  {@link de.mhus.vance.brain.script.VanceScriptApi} via reflection so
     *  the check can never drift from the real host surface. */
    private static final java.util.Set<String> VANCE_MEMBERS = collectVanceMembers();

    private static java.util.Set<String> collectVanceMembers() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (java.lang.reflect.Field f : de.mhus.vance.brain.script.VanceScriptApi.class.getDeclaredFields()) {
            if (f.isAnnotationPresent(org.graalvm.polyglot.HostAccess.Export.class)) {
                names.add(f.getName());
            }
        }
        for (java.lang.reflect.Method m : de.mhus.vance.brain.script.VanceScriptApi.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.graalvm.polyglot.HostAccess.Export.class)) {
                names.add(m.getName());
            }
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    /**
     * Scans for {@code vance.<member>} accesses and flags unknown
     * members — ERROR when it looks like a call (the failure point at
     * runtime), WARN for bare reads (could be prose in a comment that
     * survived stripping). Comments are stripped first (heuristic —
     * {@code //} not preceded by {@code :} so URLs in strings survive).
     */
    private static void collectUnknownVanceMemberIssues(ValidationRequest request, List<ValidationIssue> issues) {
        String stripped = request.code().replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(?<!:)//.*$", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bvance\\s*\\.\\s*([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(stripped);
        java.util.Set<String> reported = new java.util.TreeSet<>();
        while (m.find()) {
            String member = m.group(1);
            if (VANCE_MEMBERS.contains(member) || !reported.add(member)) {
                continue;
            }
            boolean call = stripped.substring(m.end()).stripLeading().startsWith("(");
            issues.add(new ValidationIssue(
                    call ? Severity.ERROR : Severity.WARN,
                    "unknown_vance_member",
                    "Unknown identifier 'vance." + member + "' — the vance host API has no " + "member '" + member
                            + "'. Known members: " + VANCE_MEMBERS,
                    null,
                    null));
        }
    }

    @Override
    public ValidationResult deepValidate(ValidationRequest request) {
        long startNs = System.nanoTime();
        ValidationResult basic = validate(request);
        if (!basic.ok()) {
            // Kaputtes Script — kein Sinn, LLM-Tokens zu verbrennen.
            log.debug(
                    "HactarService.deepValidate skipping LLM review " + "for '{}' — basic validate found {} error(s)",
                    request.sourceName(),
                    basic.issues().size());
            return basic;
        }

        Map<String, Object> pebbleVars = new LinkedHashMap<>();
        pebbleVars.put("code", request.code());
        pebbleVars.put("language", request.language());
        pebbleVars.put("sourceName", request.sourceName());

        Map<String, Object> reply = lightLlm.callForJson(LightLlmRequest.builder()
                .recipeName(SCRIPT_REVIEW_RECIPE)
                .userPrompt("Review the script above for semantic correctness.")
                .pebbleVars(pebbleVars)
                .schema(REVIEW_SCHEMA)
                .tenantId(request.tenantId())
                .projectId(request.projectId())
                .processId(request.processId())
                .build());

        List<ValidationIssue> llmIssues = parseLlmReviewReply(reply);
        Duration duration = Duration.ofNanos(System.nanoTime() - startNs);

        boolean ok = Boolean.TRUE.equals(reply.get("ok"))
                && llmIssues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        return ok ? new ValidationResult(true, llmIssues, duration) : ValidationResult.fail(llmIssues, duration);
    }

    // ──────────────────── Internals ────────────────────

    private void requireSupportedLanguage(ValidationRequest request) {
        if (!"js".equals(request.language())) {
            throw new IllegalArgumentException(
                    "HactarService v1 supports only language='js' — got '" + request.language() + "'");
        }
    }

    private void collectJsParseIssues(ValidationRequest request, List<ValidationIssue> issues) {
        JsValidationService.JsValidationResult result =
                jsValidationService.validate(request.code(), request.sourceName());
        if (result.ok()) return;
        for (JsValidationService.JsValidationError err : result.errors()) {
            issues.add(new ValidationIssue(
                    Severity.ERROR,
                    "syntax",
                    err.message(),
                    err.line() > 0 ? err.line() : null,
                    err.column() > 0 ? err.column() : null));
        }
    }

    private ScriptHeader collectHeaderIssues(ValidationRequest request, List<ValidationIssue> issues) {
        try {
            return ScriptHeaderParser.parse(request.code(), request.sourceName());
        } catch (ScriptExecutionException see) {
            issues.add(new ValidationIssue(Severity.ERROR, "invalid_header", see.getMessage(), null, null));
            return ScriptHeader.empty();
        }
    }

    /**
     * Parst die LLM-Antwort des {@code script-review}-Recipes in
     * {@link ValidationIssue}s. Tolerant gegen die üblichen Drift-
     * Modi: fehlendes {@code issues}-Array (= keine Issues), Items
     * als Strings statt Objects (= ERROR mit dem String als
     * Message), fehlende Severity (default ERROR), unbekannte
     * Severity (default ERROR mit Log-Warn).
     */
    private static List<ValidationIssue> parseLlmReviewReply(Map<String, Object> reply) {
        Object raw = reply.get("issues");
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        List<ValidationIssue> out = new ArrayList<>();
        for (Object item : rawList) {
            ValidationIssue parsed = toIssue(item);
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    private static @Nullable ValidationIssue toIssue(Object raw) {
        if (raw instanceof String s) {
            if (s.isBlank()) return null;
            return new ValidationIssue(Severity.ERROR, "logic", s, null, null);
        }
        if (!(raw instanceof Map<?, ?> map)) return null;

        String message = stringOrEmpty(map.get("message"));
        if (message.isBlank()) return null;

        Severity severity = parseSeverity(map.get("severity"));
        String code = stringOrEmpty(map.get("code"));
        if (code.isBlank()) code = "logic";
        Integer line = asInt(map.get("line"));
        Integer column = asInt(map.get("column"));
        return new ValidationIssue(severity, code, message, line, column);
    }

    private static Severity parseSeverity(@Nullable Object raw) {
        if (raw == null) return Severity.ERROR;
        String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        try {
            return Severity.valueOf(s);
        } catch (IllegalArgumentException iae) {
            log.warn("HactarService deepValidate reply: unknown severity '{}' " + "— treating as ERROR", raw);
            return Severity.ERROR;
        }
    }

    private static String stringOrEmpty(@Nullable Object raw) {
        return raw instanceof String s ? s : "";
    }

    private static @Nullable Integer asInt(@Nullable Object raw) {
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        return null;
    }
}
