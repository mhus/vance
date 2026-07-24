package de.mhus.vance.addon.brain.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Round-trip + field behaviour of {@link IssueCodec} (Markdown form). */
class IssueCodecTest {

    private static final String MD = "text/markdown";

    @Test
    void markdown_roundTrip_preservesFields() {
        IssueDocument i = new IssueDocument(IssueDocument.KIND, 42, "Login NPE",
                IssueDocument.STATE_OPEN, List.of("bug", "auth"), "alice", "high",
                "Repro steps.\n\n## Expected\nok", new java.util.LinkedHashMap<>());
        IssueDocument back = IssueCodec.parse(IssueCodec.serialize(i, MD), MD);
        assertThat(back.kind()).isEqualTo("issue");
        assertThat(back.number()).isEqualTo(42);
        assertThat(back.title()).isEqualTo("Login NPE");
        assertThat(back.state()).isEqualTo("open");
        assertThat(back.labels()).containsExactly("bug", "auth");
        assertThat(back.assignee()).isEqualTo("alice");
        assertThat(back.priority()).isEqualTo("high");
        assertThat(back.body()).isEqualTo("Repro steps.\n\n## Expected\nok");
    }

    @Test
    void markdown_closedState_andNoOptional_roundTrip() {
        IssueDocument i = new IssueDocument(IssueDocument.KIND, 7, "Done thing",
                IssueDocument.STATE_CLOSED, List.of(), null, null, "",
                new java.util.LinkedHashMap<>());
        String md = IssueCodec.serialize(i, MD);
        assertThat(md).contains("state: closed");
        IssueDocument back = IssueCodec.parse(md, MD);
        assertThat(back.isOpen()).isFalse();
        assertThat(back.assignee()).isNull();
        assertThat(back.labels()).isEmpty();
    }

    @Test
    void unknownFrontMatter_preservedAsExtra() {
        String md = "---\nkind: issue\nnumber: 3\nstate: open\nseverity: sev2\n---\n\nBody.";
        IssueDocument back = IssueCodec.parse(md, MD);
        assertThat(back.number()).isEqualTo(3);
        assertThat(back.extra()).containsEntry("severity", "sev2");
        assertThat(IssueCodec.serialize(back, MD)).contains("severity: sev2");
    }

    @Test
    void defaultsToOpen_whenStateMissing() {
        IssueDocument back = IssueCodec.parse("---\nkind: issue\ntitle: x\n---\n\nb", MD);
        assertThat(back.state()).isEqualTo("open");
    }
}
