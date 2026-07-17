package de.mhus.vance.api.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full template definition returned by {@code GET /brain/{tenant}/templates/{name}}
 * for Web-UI rendering. Carries the optional form fields plus the
 * name-policy so the create-dialog can prefill / lock the filename.
 *
 * <p>The Pebble body is intentionally <em>not</em> on this DTO — it
 * stays backend-only. The Web-UI submits folder + name + form values to
 * {@code POST /apply}, the brain renders the body and writes the
 * document, returning its path via {@link TemplateApplyResponseDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("template")
public class TemplateDto {

    private String name;

    /** Localized title, {@code Map<lang, text>}. */
    private Map<String, String> title;

    /** Localized one-line description, {@code Map<lang, text>}. */
    private Map<String, String> description;

    /** Heroicon name (see web-ui spec §7). */
    private @Nullable String icon;

    /** Free-form tags for picker filtering. */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** {@code free} | {@code fixed}. */
    private String nameMode;

    /**
     * For {@code nameMode=free}: the (already Pebble-rendered) filename
     * suggestion to prefill the name field, without extension. {@code null}
     * when the template carries no default.
     */
    private @Nullable String nameDefault;

    /** For {@code nameMode=fixed}: the fixed filename (incl. extension). */
    private @Nullable String nameValue;

    /**
     * Optional explicit MIME override for the created document. {@code null}
     * means the MIME is derived from the body file extension.
     */
    private @Nullable String type;

    /** Optional form fields the user fills in before apply. Empty = static template. */
    @Builder.Default
    private List<FormFieldDto> fields = new ArrayList<>();

    /** Convenience flag mirroring {@code !fields.isEmpty()} for the UI. */
    private boolean hasForm;

    /** {@code PROJECT} | {@code VANCE} | {@code RESOURCE}. */
    private String source;
}
