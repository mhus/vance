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
 * @param folder              target folder this template belongs in, or {@code null}
 *        when the caller decides. Set it for a template whose output only works
 *        in one place — a source-configuration document under
 *        {@code _vance/config/research} is read by a loader that looks exactly
 *        there, so letting the caller pick the folder can only produce a file
 *        nobody reads.
 * @param typeOverride        explicit MIME override; {@code null} = derive from body extension
 * @param app                 {@code $meta.app} discriminator this template scaffolds through
 *        {@link de.mhus.vance.brain.applications.VanceApplicationRegistry}; {@code null} for
 *        an ordinary single-document template. Mutually exclusive with a body: an app template
 *        has no body because the Java implementation owns the manifest format (and writes the
 *        derived artefacts a Pebble body could never produce).
 * @param bodyPath            normalized path of the body file (carries the extension);
 *        {@code null} exactly when {@code app} is set
 * @param bodyContent         raw Pebble body content; {@code null} exactly when {@code app} is set
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
        @Nullable String folder,
        @Nullable String typeOverride,
        List<FormFieldDto> fields,
        List<String> availableIn,
        TemplateSource source,
        @Nullable String app,
        @Nullable String bodyPath,
        @Nullable String bodyContent) {

    /** True when this template scaffolds an application instead of writing one document. */
    public boolean isApp() {
        return app != null;
    }

    /**
     * Body file extension (lowercase, without the dot) — drives the created document's type.
     * Empty for an app template, which has no body.
     */
    public String bodyExtension() {
        if (bodyPath == null) {
            return "";
        }
        int dot = bodyPath.lastIndexOf('.');
        return dot < 0 || dot == bodyPath.length() - 1
                ? ""
                : bodyPath.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
