package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.hactar.HactarService;
import de.mhus.vance.brain.hactar.HactarService.ValidationIssue;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * JavaScript script architect. Produces a single-file JS script
 * (no recipe YAML) that Hactar can execute as a standalone
 * orchestrator. The persisted artefact lives at
 * {@code _vance/scripts/_slart/<runId>/<name>.js}.
 *
 * <p>Phase 2a covers the {@code CREATE} mode only — Slart generates
 * the script from a goal + manuals. Phase 2b adds {@code UPDATE}
 * mode (existingScriptRef + failureReason).
 *
 * <p>Validation delegates to {@link HactarService#validate} — the
 * single owner of "is this script valid?" per
 * {@code planning/script-architect-executor-split.md} §5.6. The
 * architect does NOT implement parse / header / tool-allowlist
 * checks itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JsScriptArchitect implements SchemaArchitect {

    public static final String RULE_SCRIPT_JS_VALID =
            "script-js-validates-via-hactar";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            You build a single-file JavaScript orchestrator script
            from a framed goal and the subgoals you produced
            earlier. The script will be executed by Hactar as a
            standalone runtime — Hactar loads the file and runs it
            in a GraalJS sandbox with `vance.tools.call(...)` and
            `vance.process.spawn(...)` available as host APIs.

            ## Mode — CREATE vs. UPDATE

            You operate in one of two modes, detected from the
            user message:

            - **CREATE** — the user message has NO "EXISTING SCRIPT"
              section. Build a complete script from scratch using
              the framed goal and subgoals.
            - **UPDATE** — the user message contains an
              "EXISTING SCRIPT" section with the current code and
              (optionally) a "FAILURE REASON" section. Modify the
              existing script per the user description, preserving
              its structure where possible. Keep stable helper
              functions, existing variable names, and the overall
              control-flow shape unless the requested change
              demands otherwise. Re-emit the COMPLETE script body
              (not a diff) — the architect persists the new
              version to a fresh sandbox path; the caller decides
              whether to overwrite the original.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<script-name, kebab-case, no .js suffix>",
                  "code":           "<full JS source, including a JSDoc header>",
                  "justifications": {
                    "<constraint-key>": "<sg-id>",
                    "header.requiresTools": "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 sentences>"
                }

            ## JavaScript script structure (mandatory)

            The `code` field MUST start with a JSDoc header block
            that declares the runtime contract. Use ALL of these
            tags when relevant:

                /**
                 * @description <one-line human description>
                 * @version     1.0.0
                 * @timeout     <duration: 30s | 10m | 1h>
                 * @statements  <count: 1M | 5M>
                 * @requiresTools  <comma-separated tool names>
                 * @allowTools     <comma-separated tool names>
                 */

            After the header, write the orchestrator as a top-level
            IIFE (`(function() { ... })();`) or as a sequence of
            statements. **The script MUST end with a meaningful
            return value** — Hactar surfaces the IIFE's return as
            the run's result. A script that only logs and returns
            nothing is broken: the caller sees "(no return value)"
            and cannot answer the user.

            ### Parametrize inputs — DO NOT hardcode

            If the user goal mentions a concrete value
            ("quadriere 2343", "send email to alice@…", "fetch the
            last 7 days"), DO NOT bake the value into the script
            as a constant. Read it via `vance.params.<name>`
            instead. Hactar will auto-extract the value from the
            user goal text and bind it for the run.

            **Bad** (hardcoded — script is one-shot, not reusable):

                const n = 2343;
                return n * n;

            **Good** (parametrised — same script runs with any n):

                const n = vance.params.n;
                if (typeof n !== "number") {
                    throw new Error("Parameter 'n' must be a number.");
                }
                return n * n;

            The header SHOULD list every parameter the script reads
            for clarity (the runtime contract is also auto-detected
            by Hactar from the body):

                 * @description Returns the square of `n`.
                 * @param       n  — the number to square

            ### Host API surface

            - `vance.tools.call(toolName, args)` — call any tool
              listed in `@allowTools`. Returns the tool's result
              synchronously. Throws on error — wrap in try/catch
              where partial-failure recovery is meaningful.
            - `vance.process.spawn({ recipe, task, params })` — spawn
              a Vance sub-process. Use when the work needs an LLM
              loop (Arthur / Marvin / Vogon). Pure deterministic
              JS work does NOT need spawn.
            - `vance.params.<key>` — caller-supplied script parameters,
              e.g. `vance.params.n` if the caller passed
              `scriptParams: { n: 7 }`. Always defined as an object
              (empty when the caller supplied none); individual keys
              return `undefined` when absent. **Always guard with a
              `typeof`/`!= null` check before using** — the caller
              may have skipped optional params or sent the wrong type.
              Throw an explicit `Error("Missing required parameter X")`
              on a hard-required input rather than letting a downstream
              `TypeError` surface.
            - `vance.context.{tenantId, projectId, sessionId, processId}`
              — read-only scope info.
            - `vance.log.info(message, payload?)` — structured log.
            - `vance.process.progress(message, payload?)` — heartbeat
              status ping on the parent process for long-running loops.
            - `vance.process.notify(message, severity?)` — user-visible
              notification on inbox / right-rail. Use sparingly.

            ## Required tags

            - `@requiresTools` MUST list every tool the script
              actually calls via `vance.tools.call`. Hactar uses
              this to fail-fast before execution if the caller's
              effective allow-set doesn't contain a needed tool.
            - `@timeout` MUST be set — defaults are unsafe for
              long-running orchestrators.

            ## What the script MUST NOT do

            - No top-level `await` — GraalJS in v1 runs sync.
            - No `require()` / `import` / `fetch()` — sandboxed.
              All I/O is via `vance.tools.call(...)`.
            - No infinite loops without an explicit break condition
              tied to a tool-result or counter.

            ## justifications map (mandatory)

            EVERY constraint-key you set MUST point to an sg-id
            that exists in subgoals. Suggested keys:

            - "name" for the script name
            - "header.requiresTools" for each required tool group
            - "control_flow" for the top-level loop / branch shape
            - "header.timeout" for the chosen timeout magnitude

            ## confidence

            1.0 minus the speculative share — coarse heuristic.
            VALIDATING checks the script via HactarService
            (parse + header + tool allowlist).

            ## shapeRationale

            1-2 sentences explaining WHY this control-flow shape
            (linear pipeline vs. iterate-over-collection vs.
            decide-then-branch). Not per-line rationale — that's
            in the justifications.

            ## Language

            Code stays English (it's machine-read by GraalJS).
            User-facing strings inside the script (log messages,
            tool-args content) match the goal's language.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final HactarService hactarService;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.SCRIPT_JS;
    }

    @Override
    public boolean isRecipeOutput() {
        return false;
    }

    @Override
    public String outputPathSegment() {
        return "scripts";
    }

    @Override
    public String outputExtension() {
        return ".js";
    }

    @Override
    public boolean wantsPathPersistenceCheck() {
        // Scripts persist their own outputs via host-API tool calls
        // (vance.tools.call('doc_create', ...)) at runtime — not
        // through a recipe YAML the substring check could see.
        return false;
    }

    @Override
    public boolean wantsExecutionValidation() {
        // EXECUTION_VALIDATING is designed for recipe-output schemas
        // — it scans subgoals for quoted .md/.json/.yaml paths and
        // verifies the artefacts exist as Documents with ≥200 chars.
        // Scripts produce a return-value (and optionally `.js` body
        // under scripts/_slart/<runId>/) — neither matches the path
        // patterns, so the structural check would always fail and
        // drive Slart into a pointless recovery loop. Hactar's own
        // EXECUTING outcome (DONE/FAILED) is the authoritative
        // success signal for SCRIPT_JS runs.
        return false;
    }

    @Override
    public String proposingSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public boolean wantsSubRecipeListing() {
        // Scripts do not reference recipes by name in any structural
        // slot (no `allowedSubTaskRecipes`-style list). vance.process.spawn
        // accepts a recipe name as a free string and the LLM can read
        // the project recipe inventory from manuals when needed.
        return false;
    }

    @Override
    public void appendProposingContext(
            StringBuilder sb, ArchitectState state,
            List<ResolvedRecipe> availableRecipes) {
        // UPDATE-mode payload: existing script body + optional
        // failure reason from the prior Hactar run. The architect
        // is the single point where these reach the LLM — the
        // schema-agnostic ProposingPhase doesn't know they exist.
        if (state.getMode() != de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE) {
            return;
        }

        String existingCode = state.getExistingScriptCode();
        if (existingCode != null && !existingCode.isBlank()) {
            sb.append("\n\n## EXISTING SCRIPT\n\n");
            if (state.getExistingScriptRef() != null) {
                sb.append("Source path: `")
                        .append(state.getExistingScriptRef())
                        .append("`\n\n");
            }
            sb.append("```javascript\n")
                    .append(existingCode)
                    .append("\n```\n");
        }

        String priorFailureReason = state.getPriorFailureReason();
        if (priorFailureReason != null && !priorFailureReason.isBlank()) {
            sb.append("\n## FAILURE REASON\n\n")
                    .append("The previous Hactar execution of this "
                            + "script failed with:\n\n> ")
                    .append(priorFailureReason.replace("\n", "\n> "))
                    .append("\n\nAddress this failure in your update.\n");
        }
    }

    @Override
    public String expectedEngineName() {
        // Non-recipe output — ValidatingPhase skips the engine-field
        // check based on isRecipeOutput() returning false. This value
        // is never consulted in that path; returning an empty string
        // is a defensive marker rather than a real engine name.
        return "";
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft, @Nullable Map<String, Object> recipeMap,
            ThinkProcessDocument process, List<ValidationCheck> report) {
        // recipeMap is always null for SCRIPT_JS — ValidatingPhase
        // doesn't YAML-parse a script body. Work off draft.getYaml()
        // (carries the raw JS source) directly.
        String code = draft.getYaml();
        String sourceName = draft.getName() == null || draft.getName().isBlank()
                ? "<slart-script>" : draft.getName() + ".js";

        HactarService.ValidationResult result;
        try {
            result = hactarService.validate(new HactarService.ValidationRequest(
                    code,
                    "js",
                    sourceName,
                    /*callerAllowedTools*/ null,
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getId()));
        } catch (RuntimeException e) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_SCRIPT_JS_VALID).passed(false)
                    .message("HactarService.validate threw: " + e.getMessage())
                    .build();
            report.add(v);
            return v;
        }

        if (result.ok()) {
            report.add(ValidationCheck.builder()
                    .rule(RULE_SCRIPT_JS_VALID).passed(true)
                    .message("script parses cleanly and header tags are well-formed")
                    .build());
            return null;
        }

        ValidationCheck firstFail = null;
        StringBuilder summary = new StringBuilder();
        summary.append("HactarService.validate rejected the script (")
                .append(result.issues().size())
                .append(" issue(s)):\n");
        for (ValidationIssue issue : result.issues()) {
            summary.append("- [").append(issue.code()).append("] ");
            if (issue.line() != null) {
                summary.append("line ").append(issue.line());
                if (issue.column() != null) {
                    summary.append(":").append(issue.column());
                }
                summary.append(" — ");
            }
            summary.append(issue.message()).append("\n");
        }
        ValidationCheck aggregate = ValidationCheck.builder()
                .rule(RULE_SCRIPT_JS_VALID).passed(false)
                .message(summary.toString())
                .build();
        report.add(aggregate);
        firstFail = aggregate;
        return firstFail;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nEmit a corrected JavaScript script as a JSON object "
                + "with {name, code, justifications, confidence, "
                + "shapeRationale}. The `code` field must start with "
                + "a JSDoc header (`@description`, `@timeout`, "
                + "`@requiresTools`, `@allowTools`) and contain "
                + "syntactically valid JavaScript that uses ONLY "
                + "`vance.tools.call`, `vance.process.spawn`, "
                + "`vance.context.*`, and `vance.log.*` for I/O. "
                + "Every tool name in `@requiresTools` must also "
                + "appear in `@allowTools`, and every "
                + "`vance.tools.call('<name>', ...)` in the body "
                + "must reference a tool listed in `@allowTools`.";
    }

    @Override
    public DirectExecutionSpawn directExecutionSpawn(ArchitectState state) {
        // Slart EXECUTING spawns Hactar directly — the script body
        // lives at state.persistedRecipePath under
        // _vance/scripts/_slart/<runId>/<name>.js (see Phase 2a's
        // PersistingPhase wiring through architect.outputPathSegment).
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(
                de.mhus.vance.brain.hactar.HactarEngine.SCRIPT_REF_KEY,
                state.getPersistedRecipePath());
        params.put(
                de.mhus.vance.brain.hactar.HactarEngine.LANGUAGE_KEY,
                "js");
        // validateBeforeRun stays at Hactar's default false — the
        // script just survived Slart's own VALIDATING loop, no
        // point paying LLM tokens for a redundant deep-validate.
        return new DirectExecutionSpawn(
                de.mhus.vance.brain.hactar.HactarEngine.NAME, params);
    }

    @Override
    public String extractRecipeYaml(Map<String, Object> jsonRoot) {
        // The LLM emits the JS source under the `code` key (not
        // `yaml`) — the field-name reflects content type. We
        // still hand the body back through the RecipeDraft.yaml
        // slot because that's how PROPOSING / VALIDATING /
        // PERSISTING pass artefact bodies around in the schema-
        // agnostic phases.
        Object c = jsonRoot.get("code");
        if (!(c instanceof String code) || code.isBlank()) {
            throw new IllegalArgumentException(
                    "required field 'code' missing or blank — the "
                            + "JSON must carry the JS source under a "
                            + "top-level 'code' key");
        }
        return code;
    }
}
