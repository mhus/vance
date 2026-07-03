package de.mhus.vance.addon.brain.workbook.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates a {@code vance-form} fence: the {@code data} data doc
 * (kind: records), the optional {@code saveScript}, the {@code form:}
 * definition (fields + types), and that the bound records doc carries no
 * legacy {@code $meta.form} / {@code $meta.onSave}.
 */
@Component
public class FormBlockValidator implements BlockValidator {

    private static final Set<String> FIELD_TYPES = Set.of(
            "string", "textarea", "integer", "boolean", "select", "multi_select");

    @Override
    public boolean supports(String fenceType) {
        return "form".equals(fenceType);
    }

    @Override
    public List<Finding> validate(FenceBlock b, ValidationContext ctx) {
        List<Finding> out = new ArrayList<>();
        VanceRef data = Checks.docRef(out, b, ctx, "data", true, "records");
        Checks.scriptRef(out, b, ctx, "saveScript", false);
        Checks.boolAttr(out, b, "session");

        validateForm(out, b);
        checkDataDoc(out, b, ctx, data);
        return out;
    }

    private void validateForm(List<Finding> out, FenceBlock b) {
        Object form = b.attrs().get("form");
        if (!(form instanceof Map<?, ?> fm)) {
            out.add(Finding.warning(b.location(), "no-form-def",
                    "`form:` (single + fields) is missing — the form has no fields "
                            + "to render. The form definition lives in the fence."));
            return;
        }
        if (fm.get("single") != null && !(fm.get("single") instanceof Boolean)) {
            out.add(Finding.warning(b.location(), "not-boolean-single",
                    "`form.single` should be true/false."));
        }
        Object fields = fm.get("fields");
        if (!(fields instanceof List<?> list) || list.isEmpty()) {
            out.add(Finding.warning(b.location(), "no-fields",
                    "`form.fields` is empty — nothing to enter."));
            return;
        }
        int idx = 0;
        for (Object f : list) {
            idx++;
            if (!(f instanceof Map<?, ?> field)) {
                out.add(Finding.error(b.location(), "bad-field",
                        "field #" + idx + " is not a mapping."));
                continue;
            }
            Object name = field.get("name");
            if (name == null || name.toString().isBlank()) {
                out.add(Finding.error(b.location(), "field-no-name",
                        "field #" + idx + " has no `name`."));
            }
            Object type = field.get("type");
            String t = type == null ? "" : type.toString();
            if (!FIELD_TYPES.contains(t)) {
                out.add(Finding.error(b.location(), "field-bad-type",
                        "field '" + name + "' has type '" + t + "' — allowed: "
                                + FIELD_TYPES + " (use 'integer', there is no 'number')."));
            }
        }
    }

    private void checkDataDoc(
            List<Finding> out, FenceBlock b, ValidationContext ctx, VanceRef data) {
        if (data == null || !ctx.docs().exists(data.path())) return;
        Map<String, Object> doc = ctx.docs().readYaml(data.path());
        if (doc == null) return;
        Object meta = doc.get("$meta");
        if (meta instanceof Map<?, ?> m && (m.containsKey("form") || m.containsKey("onSave"))) {
            out.add(Finding.error(b.location(), "legacy-meta",
                    "data doc '" + data.path() + "' still has $meta.form/$meta.onSave — "
                            + "the form definition and saveScript belong in the fence, "
                            + "the records doc holds only schema + items."));
        }
    }
}
