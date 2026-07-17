package de.mhus.vance.api.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for {@code POST /apply}: the path and MIME of the newly
 * created document. The Web-UI opens it in the matching editor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("template")
public class TemplateApplyResponseDto {

    /** Normalized path of the created document (no leading slash). */
    private String path;

    /** Effective MIME type stored on the created document. */
    private String mimeType;
}
