package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workpage.Block;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link Block.Form}: the {@code data} records doc, the optional
 * {@code saveScript}, the {@code form} definition (fields + types), and that
 * the bound records doc carries no legacy {@code $meta.form}/{@code onSave}.
 */
@Component
public class FormBlockValidator implements BlockValidator {

    private static final Set<String> FIELD_TYPES = Set.of(
            "string", "textarea", "integer", "boolean", "select", "multi_select");

    @Override
    public boolean supports(Block block) {
        return block instanceof Block.Form;
    }

    @Override
    public List<Finding> validate(Block block, ValidationContext ctx) {
        Block.Form form = (Block.Form) block;
        List<Finding> out = new ArrayList<>();
        VanceRef data = Checks.docRef(out, ctx, "data", form.data(), true, "records");
        Checks.scriptRef(out, ctx, "saveScript", form.saveScript(), false);
        validateForm(out, ctx, form.form());
        checkDataDoc(out, ctx, data);
        return out;
    }

    private void validateForm(
            List<Finding> out, ValidationContext ctx, @Nullable Map<String, Object> form) {
        if (form == null) {
            out.add(Finding.warning(ctx.location(), "no-form-def",
                    "`form:` (single + fields) is missing — the form has no fields "
                            + "to render. The form definition lives in the fence."));
            return;
        }
        if (form.get("single") != null && !(form.get("single") instanceof Boolean)) {
            out.add(Finding.warning(ctx.location(), "not-boolean-single",
                    "`form.single` should be true/false."));
        }
        Object fields = form.get("fields");
        if (!(fields instanceof List<?> list) || list.isEmpty()) {
            out.add(Finding.warning(ctx.location(), "no-fields",
                    "`form.fields` is empty — nothing to enter."));
            return;
        }
        int idx = 0;
        for (Object f : list) {
            idx++;
            if (!(f instanceof Map<?, ?> field)) {
                out.add(Finding.error(ctx.location(), "bad-field",
                        "field #" + idx + " is not a mapping."));
                continue;
            }
            Object name = field.get("name");
            if (name == null || name.toString().isBlank()) {
                out.add(Finding.error(ctx.location(), "field-no-name",
                        "field #" + idx + " has no `name`."));
            }
            Object type = field.get("type");
            String t = type == null ? "" : type.toString();
            if (!FIELD_TYPES.contains(t)) {
                out.add(Finding.error(ctx.location(), "field-bad-type",
                        "field '" + name + "' has type '" + t + "' — allowed: "
                                + FIELD_TYPES + " (use 'integer', there is no 'number')."));
            }
        }
    }

    private void checkDataDoc(List<Finding> out, ValidationContext ctx, @Nullable VanceRef data) {
        if (data == null || !ctx.docs().exists(data.path())) return;
        Map<String, Object> doc = ctx.docs().readYaml(data.path());
        if (doc == null) return;
        Object meta = doc.get("$meta");
        if (meta instanceof Map<?, ?> m && (m.containsKey("form") || m.containsKey("onSave"))) {
            out.add(Finding.error(ctx.location(), "legacy-meta",
                    "data doc '" + data.path() + "' still has $meta.form/$meta.onSave — "
                            + "the form definition and saveScript belong in the fence, "
                            + "the records doc holds only schema + items."));
        }
    }
}
