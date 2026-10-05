package de.mhus.vance.brain.tools.fenchurch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.fenchurch.FenchurchException;
import de.mhus.vance.brain.fenchurch.FenchurchService;
import de.mhus.vance.brain.fenchurch.GenerateImageRequest;
import de.mhus.vance.brain.fenchurch.GenerateImageResult;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the {@code image_edit} tool surface: param mapping,
 * required-field enforcement, and the structured error response for
 * a model that cannot edit (the catalog gate fires inside
 * FenchurchService — here it is simulated as the exception the
 * service would throw).
 */
class ImageEditToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("tenant-x", "proj-1", "sess-1", "proc-1", "user-1", null);

    @Test
    void maps_params_to_edit_request() {
        FenchurchService service = Mockito.mock(FenchurchService.class);
        Mockito.when(service.editImage(Mockito.any()))
                .thenReturn(GenerateImageResult.builder()
                        .path("images/abc-restyled.png")
                        .mimeType("image/png")
                        .sizeBytes(1234L)
                        .modelUsed("openrouter:bytedance-seed/seedream-5-0-flash")
                        .durationMs(500L)
                        .title("Restyled")
                        .build());
        ImageEditTool tool = new ImageEditTool(service);

        Map<String, Object> result = tool.invoke(
                Map.of(
                        "prompt", "make this a watercolor",
                        "referenceDocumentIds", List.of("doc-1", "doc-2"),
                        "aspectRatio", "16:9"),
                CTX);

        assertThat(result)
                .containsEntry("path", "images/abc-restyled.png")
                .containsEntry("modelUsed", "openrouter:bytedance-seed/seedream-5-0-flash")
                .containsEntry("title", "Restyled");
        Mockito.verify(service)
                .editImage(Mockito.argThat((GenerateImageRequest r) -> "tenant-x".equals(r.getTenantId())
                        && "proj-1".equals(r.getProjectId())
                        && "make this a watercolor".equals(r.getPrompt())
                        && r.getReferenceDocumentIds().equals(List.of("doc-1", "doc-2"))
                        && "16:9".equals(r.getAspectRatio())));
    }

    @Test
    void prompt_is_required() {
        ImageEditTool tool = new ImageEditTool(Mockito.mock(FenchurchService.class));

        assertThatThrownBy(() -> tool.invoke(Map.of("referenceDocumentIds", List.of("doc-1")), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void references_are_required() {
        ImageEditTool tool = new ImageEditTool(Mockito.mock(FenchurchService.class));

        assertThatThrownBy(() -> tool.invoke(Map.of("prompt", "restyle"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("referenceDocumentIds");
    }

    @Test
    void fenchurch_exception_becomes_structured_error() {
        // The catalog gate: a model without maxInputReferences fails
        // with invalid_choice before any provider call.
        FenchurchService service = Mockito.mock(FenchurchService.class);
        Mockito.when(service.editImage(Mockito.any()))
                .thenThrow(new FenchurchException(
                        FenchurchException.Reason.INVALID_CHOICE,
                        "Model gemini:imagen-3.0-generate-002 does not accept reference images"
                                + " (maxInputReferences is 0)"));
        ImageEditTool tool = new ImageEditTool(service);

        Map<String, Object> result =
                tool.invoke(Map.of("prompt", "restyle", "referenceDocumentIds", List.of("doc-1")), CTX);

        assertThat(result)
                .containsEntry("error", "invalid_choice")
                .containsEntry("retryable", false)
                .containsEntry(
                        "message",
                        "Model gemini:imagen-3.0-generate-002 does not accept reference images"
                                + " (maxInputReferences is 0)");
    }
}
