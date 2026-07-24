package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.settings.TimezoneResolver;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemplateServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "alice";
    private static final String BODY_PATH = "_vance/templates/meeting-notes.tmpl.md";

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final FormValidator formValidator = new FormValidator();
    private final TimezoneResolver timezoneResolver = mock(TimezoneResolver.class);

    private final TemplateService service =
            new TemplateService(documentService, renderer, formValidator, timezoneResolver);

    @BeforeEach
    void setUp() {
        when(timezoneResolver.zoneId(any(), any())).thenReturn(ZoneId.of("UTC"));
        // create() echoes the path + mime it was called with, so tests can assert them.
        when(documentService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> DocumentDocument.builder()
                        .path((String) inv.getArgument(2))
                        .mimeType((String) inv.getArgument(5))
                        .build());
    }

    private ResolvedTemplate template(
            TemplateNameMode mode,
            @Nullable String nameValue,
            @Nullable String typeOverride,
            List<FormFieldDto> fields,
            String bodyPath,
            String body) {
        return new ResolvedTemplate(
                "meeting-notes",
                Map.of("en", "Meeting note"),
                Map.of("en", "Note"),
                null,
                List.of("note"),
                mode,
                /*nameDefaultTemplate*/ null,
                nameValue,
                typeOverride,
                fields,
                List.of("*"),
                TemplateSource.VANCE,
                bodyPath,
                body);
    }

    @Test
    void apply_freeName_appendsBodyExtension_andDerivesMime() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "# Hello\n");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "docs", "my-notes", Map.of(), TENANT, PROJECT, USER, "en");

        assertThat(applied.path()).isEqualTo("docs/my-notes.md");
        assertThat(applied.mimeType()).isEqualTo("text/markdown");
    }

    @Test
    void apply_freeName_keepsExplicitExtension() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "", "readme.txt", Map.of(), TENANT, PROJECT, USER, "en");

        assertThat(applied.path()).isEqualTo("readme.txt");
    }

    @Test
    void apply_fixedName_usesValueAndUserFolder() {
        ResolvedTemplate t = template(
                TemplateNameMode.FIXED, "_app.yaml", null, List.of(),
                "_vance/templates/workbook.tmpl.yaml", "$meta:\n  kind: application\n");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "my-app/", null, Map.of(), TENANT, PROJECT, USER, "en");

        assertThat(applied.path()).isEqualTo("my-app/_app.yaml");
    }

    @Test
    void apply_typeOverride_winsOverBodyExtension() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, "text/x-custom", List.of(), BODY_PATH, "x");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "docs", "n", Map.of(), TENANT, PROJECT, USER, "en");

        assertThat(applied.path()).isEqualTo("docs/n.md");
        assertThat(applied.mimeType()).isEqualTo("text/x-custom");
    }

    @Test
    void apply_rendersBodyWithFormValuesAndName() {
        FormFieldDto topic = FormFieldDto.builder()
                .name("topic").type("string").required(true)
                .label(Map.of("en", "Topic")).build();
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(topic), BODY_PATH,
                "# {{ topic }}\nfile:{{ name }}\n");

        service.apply(t, "docs", "kickoff", Map.of("topic", "Launch"), TENANT, PROJECT, USER, "en");

        // Verify the content passed to create() was fully rendered.
        var contentCaptor = org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        org.mockito.Mockito.verify(documentService).create(
                any(), any(), any(), any(), any(), any(), contentCaptor.capture(), any(), any());
        String written = new String(readAll(contentCaptor.getValue()), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(written).contains("# Launch").contains("file:kickoff").doesNotContain("{{");
    }

    @Test
    void apply_blankFreeName_throws() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        assertThatThrownBy(() -> service.apply(t, "docs", "   ", Map.of(), TENANT, PROJECT, USER, "en"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filename is required");
    }

    @Test
    void apply_existingDocument_propagatesConflict() {
        when(documentService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("exists"));
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        assertThatThrownBy(() -> service.apply(t, "docs", "dup", Map.of(), TENANT, PROJECT, USER, "en"))
                .isInstanceOf(DocumentService.DocumentAlreadyExistsException.class);
    }

    private static byte[] readAll(java.io.InputStream in) {
        try {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
