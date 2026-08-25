package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;

/**
 * The bundled {@code custom-app} template and the scaffold it dispatches to.
 *
 * <p>The centre of gravity is the scaffold, not the form: since {@code app:}
 * routing exists, the surface way and {@code bistromath_app_create} both go
 * through {@link BistromathApplication#create}, and what matters is that what
 * comes out **runs** — a view that parses, wired to a program that answers.
 */
class CustomAppTemplateTest {

    private static final String DEFINITION = "vance-defaults/_vance/templates/custom-app.yaml";
    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String FOLDER = "apps/mine";

    private final BistromathStore store = mock(BistromathStore.class);
    private final DocumentLinkBuilder linkBuilder = mock(DocumentLinkBuilder.class);
    private final RequireResolver requireResolver = mock(RequireResolver.class);
    private final BistromathApplication app =
            new BistromathApplication(store, linkBuilder, requireResolver);

    // ── definition ───────────────────────────────────────────────────

    @org.junit.jupiter.api.BeforeEach
    void requireResolverAnswersEmpty() {
        when(requireResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RequireReport.empty());
    }

    @Test
    void definition_routesThroughTheApplication() {
        Map<String, Object> spec = spec();

        assertThat(spec.get("app")).isEqualTo(BistromathApplication.APP_NAME);
        // The application owns filename and MIME; declaring either is refused
        // at load time by TemplateLoader.
        assertThat(spec).doesNotContainKeys("name", "type");
        assertThat(spec).containsKeys("title", "description");
    }

    /**
     * Two fields, and no table.
     *
     * <p>The table field is gone because there is nothing to declare: data is
     * read by the program through the document API, so a create dialog asking
     * for a table was asking about a concept the runtime no longer has.
     */
    @Test
    void definition_asksOnlyForWhatCreateReads() {
        List<FormFieldDto> fields =
                FormFieldYamlParser.parseFields(spec().get("fields"), "fields");

        assertThat(fields).extracting(FormFieldDto::getName)
                .containsExactly("title", "description");
        assertThat(fields.get(0).isRequired()).isTrue();
    }

    // ── the scaffold ─────────────────────────────────────────────────

    @Test
    void create_writesManifestViewAndProgram() {
        stubStore();

        VanceApplication.CreateResult result = app.create(ctx(values("Rechnungsbuch", "Test")));

        verify(store).writeManifest(any(), any(), any(), any(), any(), any(), any());
        List<String> written = writtenPaths();
        assertThat(written).containsExactly(
                FOLDER + "/main.yaml",
                FOLDER + "/main.js",
                FOLDER + "/_index.md");
        assertThat(result.stats()).containsEntry("viewCount", 1);
    }

    /** The manifest carries no keys: with one view there is nothing to state. */
    @Test
    void create_writesABareManifest() {
        stubStore();

        app.create(ctx(values("Notizen", null)));

        ArgumentCaptor<BistromathConfig> config = ArgumentCaptor.forClass(BistromathConfig.class);
        verify(store).writeManifest(any(), any(), any(), any(), any(), config.capture(), any());
        assertThat(config.getValue().toBlock()).isEmpty();
    }

    /**
     * The scaffolded view has to parse, and it has to be wired.
     *
     * <p>This is the assertion that would have caught the first build's
     * scaffold: that one parsed fine and did nothing, because it was a markdown
     * note about which widgets exist. Here the view must carry a button bound
     * to a function in the program and a text bound to the state key that
     * function writes — the chain, stated in the document.
     */
    @Test
    void starterView_parsesAndIsWiredToTheProgram() {
        ViewNode root = ViewParser.parse(BistromathApplication.starterView("Hello"), "main.yaml");

        ViewNode button = findByType(root, "button");
        assertThat(button).isNotNull();
        ViewAction click = button.on().get("click");
        assertThat(click).isNotNull();
        assertThat(click.kind()).isEqualTo(ActionKind.SCRIPT);
        assertThat(click.scriptRef()).isEqualTo(BistromathConfig.DEFAULT_PROGRAM);

        ViewNode text = findByType(root, "text");
        assertThat(text).isNotNull();
        assertThat(text.from()).isEqualTo("greeting");

        // Both halves must name the same function/state key as the program.
        String program = BistromathApplication.starterProgram();
        assertThat(program).contains("function " + click.function() + "(");
        assertThat(program).contains("vance.state.set('" + text.from() + "'");
        assertThat(program).contains("function init(");
    }

    /** The view declares itself a view — that is how discovery finds it. */
    @Test
    void starterView_carriesTheViewKind() {
        assertThat(BistromathApplication.starterView("Hello"))
                .contains("kind: " + BistromathConfig.VIEW_KIND);
    }

    /**
     * `init` writes something visible.
     *
     * <p>Otherwise there is no way to tell "the program ran and set up" from
     * "the program failed to load", which is the question one asks first.
     */
    @Test
    void starterProgram_initWritesTheSameKeyTheViewShows() {
        String program = BistromathApplication.starterProgram();
        int init = program.indexOf("function init(");
        int hello = program.indexOf("function hello(");

        assertThat(init).isGreaterThanOrEqualTo(0);
        assertThat(program.substring(init, hello)).contains("vance.state.set('greeting'");
    }

    // ── guards ───────────────────────────────────────────────────────

    @Test
    void create_existingManifest_isRefused() {
        stubStore();
        when(store.documentExists(any(), any(), contains("_app.yaml"))).thenReturn(true);

        assertThatThrownBy(() -> app.create(ctx(values("X", null))))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("_app.yaml")
                .hasMessageContaining("manifest");
        verify(store, never()).writeManifest(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A view or a program can outlive its manifest — somebody deletes
     * {@code _app.yaml} and leaves the rest. Writing over them goes through an
     * update, so without this check creating an app there would silently
     * replace work; the manifest guard alone does not see it.
     */
    @Test
    void create_orphanProgramInTheFolder_isRefusedByName() {
        stubStore();
        when(store.documentExists(any(), any(), contains("main.js"))).thenReturn(true);

        assertThatThrownBy(() -> app.create(ctx(values("X", null))))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("main.js")
                .hasMessageContaining("program");
    }

    @Test
    void create_withOverwrite_replacesWhatIsThere() {
        stubStore();
        when(store.documentExists(any(), any(), any())).thenReturn(true);

        app.create(new VanceApplication.CreateContext(TENANT, PROJECT, FOLDER, "alice", null,
                /*overwrite*/ true, values("X", null)));

        verify(store).writeManifest(any(), any(), any(), any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * {@code create()} ends in a {@code refresh()}, which reads back what it
     * wrote. The stub answers discovery with the view the create just wrote, so
     * the refresh sees the same app.
     */
    private void stubStore() {
        DocumentDocument manifest = DocumentDocument.builder()
                .path(FOLDER + "/_app.yaml").title("App").build();
        when(store.documentExists(any(), any(), any())).thenReturn(false);
        when(store.writeManifest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(manifest);
        when(store.writeDocument(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> DocumentDocument.builder()
                        .path((String) inv.getArgument(2)).build());
        when(store.discoverViews(any(), any(), any())).thenReturn(
                new BistromathStore.Discovered(
                        List.of(new ViewRef("main", FOLDER + "/main.yaml", "Main")),
                        List.of()));
        when(store.readView(any(), any(), any())).thenReturn(new ViewNode(
                "page", "Main", null, null, null, null, null, List.of(), List.of(), null, null,
                null, List.of(), false, Map.of(), List.of()));
        when(store.findProgram(any(), any(), any(), any())).thenReturn(
                Optional.of(DocumentDocument.builder().path(FOLDER + "/main.js").build()));
        when(store.load(any(), any(), any())).thenAnswer(inv ->
                new BistromathStore.Loaded(FOLDER, manifest,
                        new ApplicationDocument("application", BistromathApplication.APP_NAME,
                                "App", null, new LinkedHashMap<>(), new LinkedHashMap<>()),
                        BistromathConfig.empty()));
    }

    private List<String> writtenPaths() {
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).writeDocument(
                any(), any(), paths.capture(), any(), any(), any(), any(), any());
        return new ArrayList<>(paths.getAllValues());
    }

    private static @org.jspecify.annotations.Nullable ViewNode findByType(ViewNode node,
                                                                         String type) {
        if (type.equals(node.type())) return node;
        for (ViewNode child : node.children()) {
            ViewNode hit = findByType(child, type);
            if (hit != null) return hit;
        }
        return null;
    }

    private static VanceApplication.CreateContext ctx(Map<String, Object> params) {
        return new VanceApplication.CreateContext(
                TENANT, PROJECT, FOLDER, "alice", null, false, params);
    }

    private static Map<String, Object> values(String title, @org.jspecify.annotations.Nullable
                                              String description) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("title", title);
        if (description != null) v.put("description", description);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() {
        try (InputStream in = CustomAppTemplateTest.class.getClassLoader()
                .getResourceAsStream(DEFINITION)) {
            if (in == null) {
                throw new AssertionError("bundled template not on the classpath: " + DEFINITION);
            }
            return new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("could not read " + DEFINITION, e);
        }
    }
}
