package de.mhus.vance.brain.template;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parsed in-memory representation of one template — its definition YAML
 * plus the resolved Pebble body. The body stays brain-side only (never
 * serialized to the wire).
 *
 * <p>Localized fields stay as {@code Map<lang, text>} maps; resolution
 * against the caller's language happens at the controller boundary via
 * {@link de.mhus.vance.shared.form.LocalizedTexts#resolve(Map, String)}.
 *
 * @param nameDefaultTemplate Pebble template for the prefilled filename
 *        (FREE mode), without extension; {@code null} when absent
 * @param nameValue           fixed filename incl. extension (FIXED mode)
 * @param typeOverride        explicit MIME override; {@code null} = derive from body extension
 * @param bodyPath            normalized path of the body file (carries the extension)
 * @param bodyContent         raw Pebble body content
 */
public record ResolvedTemplate(
        String name,
        Map<String, String> title,
        Map<String, String> description,
        @Nullable String icon,
        List<String> tags,
        TemplateNameMode nameMode,
        @Nullable String nameDefaultTemplate,
        @Nullable String nameValue,
        @Nullable String typeOverride,
        List<FormFieldDto> fields,
        List<String> availableIn,
        TemplateSource source,
        String bodyPath,
        String bodyContent) {

    /** Body file extension (lowercase, without the dot) — drives the created document's type. */
    public String bodyExtension() {
        int dot = bodyPath.lastIndexOf('.');
        return dot < 0 || dot == bodyPath.length() - 1
                ? ""
                : bodyPath.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
