package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentExpander} — the deterministic
 * Mustache-template materializer for {@code EXPAND_FROM_DOC} nodes.
 * Mocks {@link DocumentService} and feeds inline-text bodies so the
 * codecs (parsed for real) round-trip through to {@link NodeSpec}s
 * without touching Mongo or any LLM.
 */
class DocumentExpanderTest {

    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";

    private DocumentService documentService;
    private DocumentExpander expander;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        expander = new DocumentExpander(documentService);
    }

    // ─────────────────────── list ───────────────────────

    @Test
    void list_basicMaterialization_threeItems_threeNodeSpecs() {
        stubDoc("chapters", "list", "text/markdown", """
                ---
                kind: list
                ---
                - Introduction
                - Methods
                - Results
                """);

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("chapters"),
                template("Write the chapter: {{item.text}}", "WORKER",
                        Map.of("recipe", "write-section",
                                "steerContent", "Section: {{item.text}} (idx {{index}})")),
                "RECURSIVE",
                "Write the book",
                false);

        assertThat(plan.nodes()).hasSize(3);
        assertThat(plan.nodes().get(0).children()).isEmpty();
        NodeSpec first = plan.nodes().get(0).spec();
        assertThat(first.goal()).isEqualTo("Write the chapter: Introduction");
        assertThat(first.taskKind()).isEqualTo(TaskKind.WORKER);
        assertThat(first.taskSpec())
                .containsEntry("recipe", "write-section")
                .containsEntry("steerContent", "Section: Introduction (idx 0)");
        assertThat(plan.nodes().get(2).spec().taskSpec().get("steerContent"))
                .isEqualTo("Section: Results (idx 2)");
    }

    @Test
    void list_rootAndParentVariables_resolveFromDocAndCallerGoal() {
        DocumentDocument doc = stubDoc("outline", "list", "text/markdown", """
                ---
                kind: list
                ---
                - Alpha
                - Beta
                """);
        doc.setTitle("Master Outline");

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("outline"),
                template("{{root.title}} — {{parent.goal}} — {{item.text}}",
                        "WORKER", Map.of()),
                "RECURSIVE",
                "Decompose outline",
                false);

        assertThat(plan.nodes().get(0).spec().goal())
                .isEqualTo("Master Outline — Decompose outline — Alpha");
        assertThat(plan.nodes().get(1).spec().goal())
                .isEqualTo("Master Outline — Decompose outline — Beta");
    }

    @Test
    void list_emptyDocument_returnsEmptyPlan() {
        stubDoc("empty", "list", "text/markdown", """
                ---
                kind: list
                ---
                """);

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("empty"),
                template("noop", "WORKER", Map.of()),
                "RECURSIVE", "g", false);

        assertThat(plan.nodes()).isEmpty();
    }

    @Test
    void list_missingVariableLenient_resolvesToEmptyString() {
        stubDoc("chapters", "list", "text/markdown", """
                ---
                kind: list
                ---
                - One
                """);

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("chapters"),
                template("Hello {{item.text}} ({{item.author}})", "WORKER", Map.of()),
                "RECURSIVE", null, false);

        assertThat(plan.nodes().get(0).spec().goal()).isEqualTo("Hello One ()");
    }

    @Test
    void list_missingVariableStrict_throwsExpandError() {
        stubDoc("chapters", "list", "text/markdown", """
                ---
                kind: list
                ---
                - One
                """);

        assertThatThrownBy(() -> expander.expand(
                TENANT, PROJECT,
                ref("chapters"),
                template("Hello {{item.author}}", "WORKER", Map.of()),
                "RECURSIVE", null, /*strictMissing*/ true))
                .isInstanceOf(DocumentExpander.ExpandError.class)
                .hasMessageContaining("item.author")
                .hasMessageContaining("strict");
    }

    // ─────────────────────── tree ───────────────────────

    @Test
    void tree_recursive_nestedItemsBecomeNestedTemplatedNodes() {
        stubDoc("plan", "tree", "text/markdown", """
                ---
                kind: tree
                ---
                - Chapter 1
                  - Section 1.1
                  - Section 1.2
                - Chapter 2
                """);

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("plan"),
                template("Write: {{item.text}}", "WORKER",
                        Map.of("recipe", "write-section")),
                "RECURSIVE", null, false);

        assertThat(plan.nodes()).hasSize(2);
        DocumentExpander.TemplatedNode ch1 = plan.nodes().get(0);
        assertThat(ch1.spec().goal()).isEqualTo("Write: Chapter 1");
        assertThat(ch1.children()).hasSize(2);
        assertThat(ch1.children().get(0).spec().goal()).isEqualTo("Write: Section 1.1");
        assertThat(ch1.children().get(1).spec().goal()).isEqualTo("Write: Section 1.2");
        DocumentExpander.TemplatedNode ch2 = plan.nodes().get(1);
        assertThat(ch2.spec().goal()).isEqualTo("Write: Chapter 2");
        assertThat(ch2.children()).isEmpty();
    }

    @Test
    void tree_flatMode_skipsChildrenAndOnlyMaterializesTopLevel() {
        stubDoc("plan", "tree", "text/markdown", """
                ---
                kind: tree
                ---
                - Chapter 1
                  - Section 1.1
                - Chapter 2
                """);

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("plan"),
                template("Write: {{item.text}}", "WORKER", Map.of()),
                "FLAT", null, false);

        assertThat(plan.nodes()).hasSize(2);
        assertThat(plan.nodes().get(0).children()).isEmpty();
        assertThat(plan.nodes().get(1).children()).isEmpty();
    }

    // ─────────────────────── records ───────────────────────

    @Test
    void records_substitutesSchemaFieldsViaRecordPlaceholders() {
        stubDoc("people", "records", "text/markdown", """
                ---
                kind: records
                schema: name, role
                ---
                - Alice, Author
                - Bob, Editor
                """);

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("people"),
                template("Contact {{record.name}} as {{record.role}}",
                        "WORKER",
                        Map.of("recipe", "write-email",
                                "steerContent", "Dear {{record.name}}")),
                "RECURSIVE", null, false);

        assertThat(plan.nodes()).hasSize(2);
        assertThat(plan.nodes().get(0).spec().goal()).isEqualTo("Contact Alice as Author");
        assertThat(plan.nodes().get(0).spec().taskSpec())
                .containsEntry("steerContent", "Dear Alice");
        assertThat(plan.nodes().get(1).spec().goal()).isEqualTo("Contact Bob as Editor");
    }

    // ─────────────────────── kind dispatch ───────────────────────

    @Test
    void graphKind_isExplicitlyRejected() {
        stubDoc("g", "graph", "application/json", "{\"$meta\":{\"kind\":\"graph\"}}");

        assertThatThrownBy(() -> expander.expand(
                TENANT, PROJECT,
                ref("g"),
                template("x", "WORKER", Map.of()),
                "RECURSIVE", null, false))
                .isInstanceOf(DocumentExpander.ExpandError.class)
                .hasMessageContaining("graph");
    }

    @Test
    void unknownKind_throwsExpandError() {
        stubDoc("x", "weirdkind", "text/markdown", """
                ---
                kind: weirdkind
                ---
                """);

        assertThatThrownBy(() -> expander.expand(
                TENANT, PROJECT,
                ref("x"),
                template("y", "WORKER", Map.of()),
                "RECURSIVE", null, false))
                .isInstanceOf(DocumentExpander.ExpandError.class)
                .hasMessageContaining("unsupported kind")
                .hasMessageContaining("weirdkind");
    }

    @Test
    void docWithoutKindHeader_throwsExpandError() {
        DocumentDocument doc = DocumentDocument.builder()
                .id("d-no-kind")
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path("plain")
                .name("plain")
                .mimeType("text/markdown")
                .inlineText("# Just a heading\nno kind here.")
                .build();
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("plain")))
                .thenReturn(Optional.of(doc));
        when(documentService.loadContent(any()))
                .thenAnswer(inv -> new ByteArrayInputStream(
                        ((DocumentDocument) inv.getArgument(0))
                                .getInlineText().getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> expander.expand(
                TENANT, PROJECT,
                ref("plain"),
                template("y", "WORKER", Map.of()),
                "RECURSIVE", null, false))
                .isInstanceOf(DocumentExpander.ExpandError.class)
                .hasMessageContaining("no kind header");
    }

    // ─────────────────────── lookup paths ───────────────────────

    @Test
    void documentNotFound_throwsExpandErrorWithRefDescription() {
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("missing")))
                .thenReturn(Optional.empty());
        when(documentService.listByProject(TENANT, PROJECT)).thenReturn(List.of());

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("name", "missing");

        assertThatThrownBy(() -> expander.expand(
                TENANT, PROJECT, ref,
                template("y", "WORKER", Map.of()),
                "RECURSIVE", null, false))
                .isInstanceOf(DocumentExpander.ExpandError.class)
                .hasMessageContaining("document not found")
                .hasMessageContaining("name=missing");
    }

    @Test
    void documentLookupById_usesFindByIdNotPath() {
        DocumentDocument doc = DocumentDocument.builder()
                .id("doc-42")
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path("anywhere/whatever.md")
                .name("whatever.md")
                .kind("list")
                .mimeType("text/markdown")
                .inlineText("---\nkind: list\n---\n- only\n")
                .build();
        when(documentService.findById("doc-42")).thenReturn(Optional.of(doc));
        when(documentService.loadContent(any()))
                .thenAnswer(inv -> new ByteArrayInputStream(
                        ((DocumentDocument) inv.getArgument(0))
                                .getInlineText().getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", "doc-42");

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT, ref,
                template("{{item.text}}", "WORKER", Map.of()),
                "RECURSIVE", null, false);

        assertThat(plan.nodes()).hasSize(1);
        assertThat(plan.nodes().get(0).spec().goal()).isEqualTo("only");
    }

    // ─────────────────────── childTemplate validation ───────────────────────

    @Test
    void childTemplateWithInvalidTaskKind_throwsExpandError() {
        stubDoc("chapters", "list", "text/markdown", """
                ---
                kind: list
                ---
                - One
                """);

        assertThatThrownBy(() -> expander.expand(
                TENANT, PROJECT,
                ref("chapters"),
                template("g", "NOT_A_REAL_KIND", Map.of()),
                "RECURSIVE", null, false))
                .isInstanceOf(DocumentExpander.ExpandError.class)
                .hasMessageContaining("NOT_A_REAL_KIND");
    }

    @Test
    void nestedTaskSpecMap_substitutesRecursively() {
        stubDoc("chapters", "list", "text/markdown", """
                ---
                kind: list
                ---
                - Alpha
                """);

        Map<String, Object> nestedParams = new LinkedHashMap<>();
        nestedParams.put("model", "default:fast");
        nestedParams.put("prompt", "About {{item.text}}");
        Map<String, Object> taskSpec = new LinkedHashMap<>();
        taskSpec.put("recipe", "write-section");
        taskSpec.put("params", nestedParams);
        taskSpec.put("tags", List.of("auto", "{{item.text}}"));

        DocumentExpander.ExpansionPlan plan = expander.expand(
                TENANT, PROJECT,
                ref("chapters"),
                template("g", "WORKER", taskSpec),
                "RECURSIVE", null, false);

        Map<String, Object> resolved = plan.nodes().get(0).spec().taskSpec();
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedParams = (Map<String, Object>) resolved.get("params");
        assertThat(resolvedParams)
                .containsEntry("model", "default:fast")
                .containsEntry("prompt", "About Alpha");
        @SuppressWarnings("unchecked")
        List<Object> resolvedTags = (List<Object>) resolved.get("tags");
        assertThat(resolvedTags).containsExactly("auto", "Alpha");
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Builds a {@link DocumentDocument} with the given inline text and
     * wires the {@link DocumentService} mock so {@code findByPath} and
     * {@code loadContent} return it. The path is also used as the
     * lookup name in {@link #ref(String)}.
     */
    private DocumentDocument stubDoc(String path, String kind, String mime, String body) {
        DocumentDocument doc = DocumentDocument.builder()
                .id("doc-" + path)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(path)
                .name(path)
                .kind(kind)
                .mimeType(mime)
                .inlineText(body)
                .build();
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq(path)))
                .thenReturn(Optional.of(doc));
        when(documentService.loadContent(any()))
                .thenAnswer(inv -> new ByteArrayInputStream(
                        ((DocumentDocument) inv.getArgument(0))
                                .getInlineText().getBytes(StandardCharsets.UTF_8)));
        return doc;
    }

    private static Map<String, Object> ref(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> template(
            String goal, String taskKind, Map<String, Object> taskSpec) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("goal", goal);
        t.put("taskKind", taskKind);
        t.put("taskSpec", new LinkedHashMap<>(taskSpec));
        return t;
    }
}
