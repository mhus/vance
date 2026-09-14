package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.FieldValues;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link Block.Field}: required {@code id} (uniqueness itself is
 * page-level and checked by the validation walk), {@code fieldType} in the
 * allowed set, options for the closed types, and shape/bounds of
 * {@code solution} / {@code value} against the field type. Fields without a
 * {@code solution} are valid — they are plain form elements (checklists).
 */
@Component
public class FieldBlockValidator implements BlockValidator {

    private static final Set<String> TYPES = Set.of("choice", "multi", "dropdown", "text", "textarea");
    private static final Set<String> CLOSED_TYPES = Set.of("choice", "multi", "dropdown");
    private static final Set<String> VERDICTS = Set.of("correct", "wrong");

    @Override
    public boolean supports(Block block) {
        return block instanceof Block.Field;
    }

    @Override
    public List<Finding> validate(Block block, ValidationContext ctx) {
        Block.Field fd = (Block.Field) block;
        List<Finding> out = new ArrayList<>();
        if (fd.id() == null || fd.id().isBlank()) {
            out.add(Finding.error(
                    ctx.location(),
                    "missing-id",
                    "`id` is required — form actions and results address the field by id."));
        }
        if (!TYPES.contains(fd.fieldType())) {
            out.add(Finding.error(
                    ctx.location(),
                    "bad-type",
                    "`type: " + fd.fieldType() + "` is not supported — use 'choice', 'multi', "
                            + "'dropdown', 'text' or 'textarea'."));
        }
        if (fd.question() == null || fd.question().isBlank()) {
            out.add(Finding.warning(
                    ctx.location(), "missing-question", "`question` is empty — the field has no label."));
        }
        if (CLOSED_TYPES.contains(fd.fieldType()) && fd.options().size() < 2) {
            out.add(Finding.error(
                    ctx.location(),
                    "missing-options",
                    "`options` needs at least 2 entries for type '" + fd.fieldType() + "'."));
        }
        checkPayload(out, ctx, fd, "solution", fd.solution());
        checkPayload(out, ctx, fd, "value", fd.value());
        if (fd.verdict() != null && !VERDICTS.contains(fd.verdict())) {
            out.add(Finding.error(
                    ctx.location(),
                    "bad-verdict",
                    "`verdict: " + fd.verdict() + "` is not supported — 'correct' or 'wrong'."));
        }
        return out;
    }

    /** Shape + bounds check of one dynamic payload ({@code solution} / {@code value}). */
    private void checkPayload(List<Finding> out, ValidationContext ctx, Block.Field fd, String label, Object payload) {
        if (payload == null) return;
        switch (fd.fieldType()) {
            case "choice", "dropdown" -> {
                Integer idx = FieldValues.asIndex(payload);
                if (idx == null) {
                    out.add(Finding.error(
                            ctx.location(),
                            "bad-" + label,
                            "`" + label + "` must be a single option index (integer)."));
                } else if (!FieldValues.inBounds(idx, fd.options().size())) {
                    out.add(Finding.error(
                            ctx.location(),
                            "out-of-bounds-" + label,
                            "`" + label + ": " + idx + "` is outside the options (0.."
                                    + (fd.options().size() - 1) + ")."));
                }
            }
            case "multi" -> {
                List<Integer> idxs = FieldValues.asIndices(payload);
                if (idxs == null) {
                    out.add(Finding.error(
                            ctx.location(), "bad-" + label, "`" + label + "` must be a list of option indices."));
                } else {
                    for (int idx : idxs) {
                        if (!FieldValues.inBounds(idx, fd.options().size())) {
                            out.add(Finding.error(
                                    ctx.location(),
                                    "out-of-bounds-" + label,
                                    "`" + label + "` contains index " + idx
                                            + " outside the options (0.."
                                            + (fd.options().size() - 1) + ")."));
                            break;
                        }
                    }
                }
            }
            case "text", "textarea" -> {
                if (!(payload instanceof String)) {
                    out.add(Finding.error(
                            ctx.location(),
                            "bad-" + label,
                            "`" + label + "` must be a string for type '" + fd.fieldType() + "'."));
                }
            }
            default -> {
                // unknown fieldType — already reported as bad-type
            }
        }
    }
}
