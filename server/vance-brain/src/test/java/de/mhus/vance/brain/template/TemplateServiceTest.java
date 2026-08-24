package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import de.mhus.vance.shared.settings.TimezoneResolver;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemplateServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "alice";
    private static final SecurityContext SUBJECT = SecurityContext.user(USER, TENANT, List.of());
    private static final String BODY_PATH = "_vance/templates/meeting-notes.tmpl.md";

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final FormValidator formValidator = new FormValidator();
    private final TimezoneResolver timezoneResolver = mock(TimezoneResolver.class);

    private final VanceApplicationRegistry applicationRegistry = mock(VanceApplicationRegistry.class);
    private final PermissionService permissionService = mock(PermissionService.class);

    private final TemplateService service = new TemplateService(
            documentService, renderer, formValidator, timezoneResolver,
            applicationRegistry, permissionService);

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
        return template(mode, nameValue, /*folder*/ null, typeOverride, fields, bodyPath, body);
    }

    private ResolvedTemplate template(
            TemplateNameMode mode,
            @Nullable String nameValue,
            @Nullable String folder,
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
                folder,
                typeOverride,
                fields,
                List.of("*"),
                TemplateSource.VANCE,
                /*app*/ null,
                bodyPath,
                body);
    }

    /** An app template: the {@code app} discriminator and no body at all. */
    private ResolvedTemplate appTemplate(String app, @Nullable String folder, List<FormFieldDto> fields) {
        return new ResolvedTemplate(
                app,
                Map.of("en", "App"),
                Map.of("en", "An app"),
                null,
                List.of("app"),
                TemplateNameMode.FIXED,
                /*nameDefaultTemplate*/ null,
                VanceApplication.APP_MANIFEST,
                folder,
                /*typeOverride*/ null,
                fields,
                List.of("*"),
                TemplateSource.VANCE,
                app,
                /*bodyPath*/ null,
                /*bodyContent*/ null);
    }

    @Test
    void apply_freeName_appendsBodyExtension_andDerivesMime() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "# Hello\n");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "docs", "my-notes", Map.of(), TENANT, PROJECT, SUBJECT, "en");

        assertThat(applied.path()).isEqualTo("docs/my-notes.md");
        assertThat(applied.mimeType()).isEqualTo("text/markdown");
    }

    @Test
    void apply_declaredFolder_overridesTheCallerFolder() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, "_vance/config/research", null,
                List.of(), "research-arxiv.tmpl.yaml", "protocol: arxiv\n");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "documents", "arxiv", Map.of(), TENANT, PROJECT, SUBJECT, "en");

        assertThat(applied.path()).isEqualTo("_vance/config/research/arxiv.yaml");
    }

    @Test
    void apply_freeName_keepsExplicitExtension() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "", "readme.txt", Map.of(), TENANT, PROJECT, SUBJECT, "en");

        assertThat(applied.path()).isEqualTo("readme.txt");
    }

    @Test
    void apply_fixedName_usesValueAndUserFolder() {
        ResolvedTemplate t = template(
                TemplateNameMode.FIXED, "_app.yaml", null, List.of(),
                "_vance/templates/workbook.tmpl.yaml", "$meta:\n  kind: application\n");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "my-app/", null, Map.of(), TENANT, PROJECT, SUBJECT, "en");

        assertThat(applied.path()).isEqualTo("my-app/_app.yaml");
    }

    @Test
    void apply_typeOverride_winsOverBodyExtension() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, "text/x-custom", List.of(), BODY_PATH, "x");

        TemplateService.AppliedTemplate applied = service.apply(
                t, "docs", "n", Map.of(), TENANT, PROJECT, SUBJECT, "en");

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

        service.apply(t, "docs", "kickoff", Map.of("topic", "Launch"), TENANT, PROJECT, SUBJECT, "en");

        // Verify the content passed to create() was fully rendered.
        var contentCaptor = org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        org.mockito.Mockito.verify(documentService).create(
                any(), any(), any(), any(), any(), any(), contentCaptor.capture(), any(), any());
        String written = new String(readAll(contentCaptor.getValue()), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(written).contains("# Launch").contains("file:kickoff").doesNotContain("{{");
    }

    @Test
    void apply_writesWithUserReasonActor_notSystemBypass() {
        // Security regression (code-review-2 B3): the target folder is
        // caller-chosen, so the write must carry the authenticated subject with
        // WriteReason.USER — NOT WriteActor.SYSTEM, which would fail-open past the
        // reserved-prefix (R4) ADMIN gate and let a WRITER plant _vance/ docs.
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        service.apply(t, "docs", "n", Map.of(), TENANT, PROJECT, SUBJECT, "en");

        ArgumentCaptor<WriteActor> actor = ArgumentCaptor.forClass(WriteActor.class);
        verify(documentService).create(
                any(), any(), any(), any(), any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue().reason()).isEqualTo(WriteReason.USER);
        assertThat(actor.getValue().subject()).isEqualTo(SUBJECT);
    }

    @Test
    void apply_blankFreeName_throws() {
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        assertThatThrownBy(() -> service.apply(t, "docs", "   ", Map.of(), TENANT, PROJECT, SUBJECT, "en"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filename is required");
    }

    @Test
    void apply_existingDocument_propagatesConflict() {
        when(documentService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("exists"));
        ResolvedTemplate t = template(
                TemplateNameMode.FREE, null, null, List.of(), BODY_PATH, "x");

        assertThatThrownBy(() -> service.apply(t, "docs", "dup", Map.of(), TENANT, PROJECT, SUBJECT, "en"))
                .isInstanceOf(DocumentService.DocumentAlreadyExistsException.class);
    }

    // ──────────────────── app templates ────────────────────

    private VanceApplication stubApp(String name) {
        VanceApplication app = mock(VanceApplication.class);
        when(app.appName()).thenReturn(name);
        when(app.create(any())).thenAnswer(inv -> {
            VanceApplication.CreateContext ctx = inv.getArgument(0);
            return new VanceApplication.CreateResult(
                    name, ctx.folder(), ctx.folder() + "/_app.yaml",
                    null, List.of(), List.of(), null, Map.of());
        });
        when(applicationRegistry.require(name)).thenReturn(app);
        return app;
    }

    @Test
    void apply_appTemplate_dispatchesToTheApplication_insteadOfWritingADocument() {
        VanceApplication app = stubApp("kanban");
        when(documentService.findByPath(any(), any(), any())).thenReturn(java.util.Optional.empty());

        TemplateService.AppliedTemplate applied = service.apply(
                appTemplate("kanban", null, List.of()),
                "boards/sprint/", null, Map.of("title", "Sprint"),
                TENANT, PROJECT, SUBJECT, "en");

        // The manifest comes from the application — the service writes nothing itself.
        assertThat(applied.path()).isEqualTo("boards/sprint/_app.yaml");
        assertThat(applied.mimeType()).contains("yaml");
        org.mockito.Mockito.verify(documentService, org.mockito.Mockito.never()).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<VanceApplication.CreateContext> ctx =
                ArgumentCaptor.forClass(VanceApplication.CreateContext.class);
        verify(app).create(ctx.capture());
        assertThat(ctx.getValue().folder()).isEqualTo("boards/sprint");
        assertThat(ctx.getValue().projectName()).isEqualTo(PROJECT);
        assertThat(ctx.getValue().userId()).isEqualTo(USER);
        assertThat(ctx.getValue().overwrite()).isFalse();
        assertThat(ctx.getValue().params()).containsEntry("title", "Sprint");
    }

    @Test
    void apply_appTemplate_enforcesCreateOnTheManifest_beforeDispatch() {
        // CreateContext carries only a userId, and the applications derive their
        // write actor from it — a blank one degrades to SecurityContext.SYSTEM.
        // So this surface must do the check itself, or a service-account caller
        // would write past the permission provider.
        stubApp("kanban");
        when(documentService.findByPath(any(), any(), any())).thenReturn(java.util.Optional.empty());

        service.apply(appTemplate("kanban", null, List.of()),
                "boards", null, Map.of(), TENANT, PROJECT, SUBJECT, "en");

        verify(permissionService).enforce(
                org.mockito.ArgumentMatchers.eq(SUBJECT),
                org.mockito.ArgumentMatchers.eq(
                        new de.mhus.vance.shared.permission.Resource.Document(
                                TENANT, PROJECT, "boards/_app.yaml")),
                org.mockito.ArgumentMatchers.eq(de.mhus.vance.shared.permission.Action.CREATE));
    }

    @Test
    void apply_appTemplate_existingManifest_conflictsBeforeTheAppIsCalled() {
        // Every application raises its own ToolException for an occupied
        // manifest; checking here keeps one 409 for all of them.
        VanceApplication app = stubApp("kanban");
        when(documentService.findByPath(TENANT, PROJECT, "boards/_app.yaml"))
                .thenReturn(java.util.Optional.of(DocumentDocument.builder().path("boards/_app.yaml").build()));

        assertThatThrownBy(() -> service.apply(appTemplate("kanban", null, List.of()),
                "boards", null, Map.of(), TENANT, PROJECT, SUBJECT, "en"))
                .isInstanceOf(DocumentService.DocumentAlreadyExistsException.class);
        org.mockito.Mockito.verify(app, org.mockito.Mockito.never()).create(any());
    }

    @Test
    void apply_appTemplate_declaredFolderWinsOverTheCallerFolder() {
        VanceApplication app = stubApp("feeds");
        when(documentService.findByPath(any(), any(), any())).thenReturn(java.util.Optional.empty());

        service.apply(appTemplate("feeds", "pinned/news", List.of()),
                "somewhere/else", null, Map.of(), TENANT, PROJECT, SUBJECT, "en");

        ArgumentCaptor<VanceApplication.CreateContext> ctx =
                ArgumentCaptor.forClass(VanceApplication.CreateContext.class);
        verify(app).create(ctx.capture());
        assertThat(ctx.getValue().folder()).isEqualTo("pinned/news");
    }

    @Test
    void apply_appTemplate_withoutAnyFolder_throws() {
        stubApp("kanban");

        assertThatThrownBy(() -> service.apply(appTemplate("kanban", null, List.of()),
                "  ", null, Map.of(), TENANT, PROJECT, SUBJECT, "en"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("folder is required");
    }

    @Test
    void apply_appTemplate_integerFieldReachesTheAppAsANumber() {
        // The web form seeds and submits every value as a string, while the
        // applications read their params with `instanceof Number`. Without the
        // coercion "10" is silently ignored in favour of the app's own default.
        VanceApplication app = stubApp("search");
        when(documentService.findByPath(any(), any(), any())).thenReturn(java.util.Optional.empty());
        FormFieldDto num = FormFieldDto.builder()
                .name("defaultNum").type("integer")
                .label(Map.of("en", "Results")).build();

        service.apply(appTemplate("search", null, List.of(num)),
                "search", null, Map.of("defaultNum", "10"), TENANT, PROJECT, SUBJECT, "en");

        ArgumentCaptor<VanceApplication.CreateContext> ctx =
                ArgumentCaptor.forClass(VanceApplication.CreateContext.class);
        verify(app).create(ctx.capture());
        assertThat(ctx.getValue().params()).containsEntry("defaultNum", 10);
    }

    private static byte[] readAll(java.io.InputStream in) {
        try {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
