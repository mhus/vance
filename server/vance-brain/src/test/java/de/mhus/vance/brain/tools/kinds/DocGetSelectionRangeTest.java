package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.client.CortexTurnSelectionHolder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code doc_get_selection} slices the body with {@code substring()}, so its
 * range is measured in <b>characters</b>. The parameters are therefore
 * {@code fromChar}/{@code toChar}.
 *
 * <p>The naming matters more than usual here. A line-flavoured spelling
 * ({@code fromLine}/{@code toLine}, which the vocabulary sweep briefly gave this
 * tool) reads as correct to a caller and is then silently wrong: a line number
 * passed as a character offset is clamped into range and answers with the wrong
 * text — no error, no hint. Hence a test that pins both the unit and the
 * still-readable old spellings.
 */
class DocGetSelectionRangeTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    /** Line 1 is 6 chars incl. newline, so char 7 is 'b' — line ≠ char here. */
    private static final String BODY = "alpha\nbravo\ncharlie\n";

    private KindToolSupport support;
    private CortexTurnSelectionHolder holder;
    private DocGetSelectionTool tool;

    @BeforeEach
    void setUp() {
        support = mock(KindToolSupport.class);
        holder = mock(CortexTurnSelectionHolder.class);

        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-1");
        doc.setPath("documents/notes.md");
        when(support.loadDocument(any(), eq(CTX))).thenReturn(doc);
        when(support.readBody(doc, CTX)).thenReturn(BODY);

        tool = new DocGetSelectionTool(support, holder);
    }

    private static Map<String, Object> params(String fromKey, Object from, String toKey, Object to) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", "documents/notes.md");
        p.put(fromKey, from);
        p.put(toKey, to);
        return p;
    }

    @Test
    void schemaDeclaresCharacterNames_notLineNames() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) tool.paramsSchema().get("properties");

        assertThat(props).containsKeys("fromChar", "toChar");
        assertThat(props).doesNotContainKeys("fromLine", "toLine", "from", "to");
    }

    @Test
    void fromCharToChar_areCharacterOffsets_notLineNumbers() {
        Map<String, Object> out = tool.invoke(params("fromChar", 6, "toChar", 11), CTX);

        // Characters 6..11 = "bravo". A line-indexed reading would have returned
        // something else entirely — that is the bug this name prevents.
        assertThat(out.get("text")).isEqualTo("bravo");
        assertThat(out.get("from")).isEqualTo(6);
        assertThat(out.get("to")).isEqualTo(11);
    }

    @Test
    void endOffsetIsExclusive() {
        Map<String, Object> out = tool.invoke(params("fromChar", 0, "toChar", 5), CTX);

        assertThat(out.get("text")).isEqualTo("alpha");
    }

    @Test
    void legacyFromTo_areStillRead() {
        // The spelling this tool originally shipped with. Undeclared, still honoured.
        Map<String, Object> out = tool.invoke(params("from", 6, "to", 11), CTX);

        assertThat(out.get("text")).isEqualTo("bravo");
    }

    @Test
    void legacyFromLineToLine_areStillRead() {
        // The short-lived rename. Still honoured so in-flight calls don't break,
        // but never offered in the schema.
        Map<String, Object> out = tool.invoke(params("fromLine", 6, "toLine", 11), CTX);

        assertThat(out.get("text")).isEqualTo("bravo");
    }

    @Test
    void canonicalNameWinsOverALegacyOne() {
        Map<String, Object> p = params("fromChar", 0, "toChar", 5);
        p.put("fromLine", 6);
        p.put("toLine", 11);

        assertThat(tool.invoke(p, CTX).get("text")).isEqualTo("alpha");
    }

    @Test
    void rangeBeyondTheBody_isClampedRatherThanThrowing() {
        Map<String, Object> out = tool.invoke(params("fromChar", 12, "toChar", 9999), CTX);

        assertThat(out.get("text")).isEqualTo("charlie\n");
        assertThat(out.get("to")).isEqualTo(BODY.length());
    }
}
