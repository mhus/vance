package de.mhus.vance.addon.brain.workbook.action;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.FieldValues;
import de.mhus.vance.addon.brain.workpage.WorkPageDocument;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code vance-button} {@code type: form-resolve} — grades the page's
 * {@code vance-field} blocks and writes {@code verdict} (+ {@code feedback})
 * directly into the page markdown; the open editor picks the change up via
 * the documents channel. Two grading paths:
 *
 * <ul>
 *   <li><b>Closed types</b> ({@code choice}/{@code multi}/{@code dropdown})
 *       with a {@code solution}: mechanical index comparison, no LLM.</li>
 *   <li><b>Free-text types</b> ({@code text}/{@code textarea}) with a
 *       {@code judge} fence map: graded by the internal {@code form-judge}
 *       LightLlm profile (Jeltz-style JSON loop) against
 *       {@code judge.criteria} (+ the {@code solution} reference if
 *       present). Without a {@code judge} a text field is not graded — a
 *       bare {@code solution} is a human-readable reference.</li>
 * </ul>
 *
 * <p>A blank answer counts as {@code wrong} without spending an LLM call.
 * Fields whose LLM grading fails do not abort the resolve — they are
 * skipped and reported in the summary message.
 */
@Component
@Slf4j
public class FormResolveActionHandler implements ButtonActionHandler {

    /** Bundled internal LightLlm recipe (tenant/project may override by path). */
    static final String JUDGE_RECIPE = "form-judge";

    /** JSON-schema-light contract for the judge reply. */
    private static final Map<String, Object> JUDGE_SCHEMA = Map.of(
            "type",
            "object",
            "required",
            List.of("verdict", "feedback"),
            "properties",
            Map.of(
                    "verdict", Map.of("type", "string", "enum", List.of("correct", "wrong")),
                    "feedback", Map.of("type", "string")));

    private final WorkPageService workPageService;
    private final LightLlmService lightLlmService;

    public FormResolveActionHandler(WorkPageService workPageService, LightLlmService lightLlmService) {
        this.workPageService = workPageService;
        this.lightLlmService = lightLlmService;
    }

    @Override
    public String type() {
        return "form-resolve";
    }

    /** One field's grade: verdict plus optional judge feedback. */
    private record Grade(String verdict, @Nullable String feedback) {}

    @Override
    public ButtonActionResult run(ButtonActionContext ctx) {
        DocumentDocument doc = workPageService.requireByPath(ctx.tenantId(), ctx.projectId(), ctx.pagePath());
        WorkPageDocument page = workPageService.readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        int[] checkable = {0};
        int[] correct = {0};
        int[] judgeFailed = {0};
        boolean changed = FieldWalk.walk(blocks, field -> {
            Grade grade = grade(field, ctx, judgeFailed);
            if (grade == null) return null; // not checkable, or judge failed
            checkable[0]++;
            if ("correct".equals(grade.verdict())) correct[0]++;
            boolean verdictChanged = !grade.verdict().equals(field.verdict());
            boolean feedbackChanged =
                    grade.feedback() != null && !grade.feedback().equals(field.feedback());
            if (!verdictChanged && !feedbackChanged) return null;
            return new Block.Field(
                    field.id(),
                    field.fieldType(),
                    field.question(),
                    field.options(),
                    field.solution(),
                    field.value(),
                    grade.verdict(),
                    grade.feedback(),
                    field.judge());
        });
        if (checkable[0] == 0 && judgeFailed[0] == 0) {
            return new ButtonActionResult("Nothing to check — add a `solution` (closed types) or a `judge` config "
                    + "(free text) to the fields you want graded.");
        }
        if (changed) {
            workPageService.writeDocument(doc, page.withBlocks(blocks));
        }
        String message =
                checkable[0] + " of " + (checkable[0] + judgeFailed[0]) + " answers graded, " + correct[0] + " correct";
        if (judgeFailed[0] > 0) {
            message += " — " + judgeFailed[0] + " field(s) could not be graded (LLM judge failed)";
        }
        return new ButtonActionResult(message);
    }

    /**
     * Grade one field — {@code null} when the field is not checkable (closed
     * type without {@code solution}; free text without {@code judge}) or when
     * the LLM judge failed (the field is skipped, not aborted).
     */
    private Grade grade(Block.Field field, ButtonActionContext ctx, int[] judgeFailed) {
        return switch (field.fieldType()) {
            case "choice", "dropdown" -> {
                if (field.solution() == null) yield null;
                yield new Grade(
                        Objects.equals(FieldValues.asIndex(field.value()), FieldValues.asIndex(field.solution()))
                                ? "correct"
                                : "wrong",
                        null);
            }
            case "multi" -> {
                if (field.solution() == null) yield null;
                yield new Grade(
                        Objects.equals(FieldValues.asIndices(field.value()), FieldValues.asIndices(field.solution()))
                                ? "correct"
                                : "wrong",
                        null);
            }
            case "text", "textarea" -> {
                if (field.judge() == null || field.judge().isEmpty()) yield null;
                if (!(field.value() instanceof String answer) || answer.isBlank()) {
                    // Blank answer: wrong, without spending an LLM call.
                    yield new Grade("wrong", null);
                }
                try {
                    yield judgeByLlm(field, answer, ctx);
                } catch (LightLlmException e) {
                    judgeFailed[0]++;
                    log.warn(
                            "form-resolve judge failed for field '{}' on '{}': {}",
                            field.id(),
                            ctx.pagePath(),
                            e.getMessage());
                    yield null;
                }
            }
            default -> null;
        };
    }

    /** One free-text grading call over the internal judge recipe. */
    private Grade judgeByLlm(Block.Field field, String answer, ButtonActionContext ctx) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(field.question()).append("\n\n");
        if (field.solution() instanceof String ref && !ref.isBlank()) {
            prompt.append("Reference answer (one correct wording, not the only acceptable one):\n")
                    .append(ref)
                    .append("\n\n");
        }
        Object criteria = field.judge().get("criteria");
        if (criteria != null && !String.valueOf(criteria).isBlank()) {
            prompt.append("Grading criteria:\n").append(criteria).append("\n\n");
        }
        prompt.append("Student's answer:\n").append(answer);

        Map<String, Object> out = lightLlmService.callForJson(LightLlmRequest.builder()
                .recipeName(JUDGE_RECIPE)
                .userPrompt(prompt.toString())
                .schema(JUDGE_SCHEMA)
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .build());
        String verdict =
                out.get("verdict") instanceof String v && ("correct".equals(v) || "wrong".equals(v)) ? v : "wrong";
        String feedback = out.get("feedback") instanceof String f && !f.isBlank() ? f : null;
        return new Grade(verdict, feedback);
    }
}
