package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.KindRegistry;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@code kind} parameter of {@code doc_write} and {@code doc_create_kind}
 * must name the kinds that are <b>registered</b>, not a list written into the
 * source.
 *
 * <p>The registry is open — an addon adds a kind by contributing a handler — so
 * any enumeration in the code is out of date the moment one does. The failure
 * mode is not a crash: an agent reads the list, does not find `app-view`, and
 * reports that the kind does not exist. That is the bug these two tests exist
 * to keep fixed.
 */
class KindListInSchemaTest {

    private static KindRegistry registryWith(String... names) {
        KindRegistry r = mock(KindRegistry.class);
        when(r.names()).thenReturn(new LinkedHashSet<>(Set.of(names)));
        return r;
    }

    @SuppressWarnings("unchecked")
    private static String kindDescription(Map<String, Object> schema) {
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        return String.valueOf(kind.get("description"));
    }

    @Test
    void docWrite_namesAnAddonKind() {
        DocWriteTool tool = new DocWriteTool(null, null,
                registryWith("text", "records", "app-view"), null);

        assertThat(kindDescription(tool.paramsSchema())).contains("app-view");
    }

    @Test
    void docCreateKind_namesAnAddonKind() {
        DocCreateKindTool tool = new DocCreateKindTool(null, null,
                registryWith("text", "records", "finance-tree"));

        assertThat(kindDescription(tool.paramsSchema())).contains("finance-tree");
    }

    @Test
    void docCreateKind_noLongerCarriesAClosedListInTheSource() {
        // It used to say "One of: list, checklist, …" — an assertion that was
        // already wrong for `diagram` and `application`.
        DocCreateKindTool tool = new DocCreateKindTool(null, null, registryWith("text"));

        assertThat(kindDescription(tool.paramsSchema())).doesNotContain("checklist");
    }

    @Test
    void schema_isStableAcrossCalls() {
        // The description is part of the prompt prefix. One that differed
        // between turns would break the cache for every request after it.
        DocWriteTool tool = new DocWriteTool(null, null, registryWith("text", "app-view"), null);

        assertThat(tool.paramsSchema()).isSameAs(tool.paramsSchema());
    }

    @Test
    void schema_survivesAnEmptyRegistry() {
        // Not a real deployment, but the fallback must not produce
        // "One of: ." — an empty list reads as "no kinds exist".
        DocWriteTool tool = new DocWriteTool(null, null, registryWith(), null);

        assertThat(kindDescription(tool.paramsSchema())).doesNotContain("Registered here");
    }
}
