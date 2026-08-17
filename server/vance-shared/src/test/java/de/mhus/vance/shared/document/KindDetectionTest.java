package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.mhus.vance.shared.document.kind.DiagramCodec;
import de.mhus.vance.shared.document.kind.KindHandler;

/**
 * Detection of a document's kind from its body — the path taken when a
 * create carries no explicit {@code kind}.
 */
class KindDetectionTest {

    /** Minimal handler that claims whatever contains its marker. */
    private static KindHandler handler(String name, String marker, int priority) {
        return new KindHandler() {
            @Override public String getName() {
                return name;
            }

            @Override public boolean detects(String content) {
                return content.contains(marker);
            }

            @Override public int detectionPriority() {
                return priority;
            }
        };
    }

    private static KindRegistry registryOf(KindHandler... handlers) {
        KindRegistry r = new KindRegistry(List.of(handlers));
        r.collect();
        return r;
    }

    @Test
    void detectKind_mermaidFence_isClaimedByDiagram() {
        KindRegistry registry = registryOf(
                () -> "text",
                new KindHandler() {
                    @Override public String getName() {
                        return "diagram";
                    }

                    @Override public boolean detects(String content) {
                        return DiagramCodec.looksLikeDiagram(content);
                    }
                });

        assertThat(registry.detectKind("""
                # Login flow

                ```mermaid
                flowchart TD
                  A --> B
                ```
                """)).isEqualTo("diagram");
    }

    @Test
    void detectKind_proseMentioningMermaid_isNotClaimed() {
        // Only the fence language counts. Prose about diagrams is prose —
        // a looser rule would mistype ordinary notes.
        KindRegistry registry = registryOf(
                () -> "text",
                new KindHandler() {
                    @Override public String getName() {
                        return "diagram";
                    }

                    @Override public boolean detects(String content) {
                        return DiagramCodec.looksLikeDiagram(content);
                    }
                });

        assertThat(registry.detectKind(
                "We should draw this as a mermaid flowchart later."))
                .isNull();
    }

    @Test
    void looksLikeDiagram_fenceNestedInALongerFence_isNotAMarker() {
        // A manual that SHOWS how to write a mermaid block wraps it in a
        // four-backtick fence. Claiming that would file the documentation
        // about diagrams as a diagram.
        assertThat(DiagramCodec.looksLikeDiagram("""
                Write a diagram like this:

                ````markdown
                ```mermaid
                flowchart TD
                ```
                ````
                """)).isFalse();
    }

    @Test
    void looksLikeDiagram_fourSpaceIndentedFence_isAnExampleNotAMarker() {
        // Four columns of indent is an indented code block in CommonMark —
        // the line is shown verbatim, it opens nothing.
        assertThat(DiagramCodec.looksLikeDiagram("""
                Example:

                    ```mermaid
                    flowchart TD
                    ```
                """)).isFalse();
    }

    @Test
    void looksLikeDiagram_fenceIndentedInsideAListItem_stillCounts() {
        // Up to three columns is still a fence — a diagram under a bullet
        // is a real diagram.
        assertThat(DiagramCodec.looksLikeDiagram("""
                - the flow:

                  ```mermaid
                  flowchart TD
                  ```
                """)).isTrue();
    }

    @Test
    void looksLikeDiagram_afterAClosedCodeBlock_stillCounts() {
        // The fence tracker must reopen: a closed block leaves no state
        // behind, so a later mermaid fence is claimed as before.
        assertThat(DiagramCodec.looksLikeDiagram("""
                ```json
                {"a": 1}
                ```

                ```mermaid
                flowchart TD
                ```
                """)).isTrue();
    }

    @Test
    void detectKind_ambiguousShortBody_lowestPriorityWins() {
        // The case that makes "first wins" load-bearing: `- a` is a
        // plausible list, checklist and tree at once. Priority decides,
        // and it must be the declared one — not injection order.
        KindRegistry registry = registryOf(
                handler("tree", "- ", 50),
                handler("checklist", "- ", 30),
                handler("list", "- ", 20));

        assertThat(registry.detectKind("- a\n- b")).isEqualTo("list");
    }

    @Test
    void detectKind_samePriority_kindNameBreaksTheTie() {
        // A total order even when two kinds forget to differentiate
        // themselves: alphabetical, so the winner never depends on which
        // addon happened to register first.
        KindRegistry registry = registryOf(
                handler("zeta", "x", 100),
                handler("alpha", "x", 100));

        assertThat(registry.detectKind("x")).isEqualTo("alpha");
    }

    @Test
    void detectKind_injectionOrderDoesNotDecide() {
        // Same handlers, reversed injection order, same answer.
        KindHandler tree = handler("tree", "- ", 50);
        KindHandler list = handler("list", "- ", 20);

        assertThat(registryOf(tree, list).detectKind("- a")).isEqualTo("list");
        assertThat(registryOf(list, tree).detectKind("- a")).isEqualTo("list");
    }

    @Test
    void detectKind_throwingDetector_isSkippedNotPropagated() {
        // Detection is a convenience on the write path; a broken detector
        // must never fail the write.
        KindHandler broken = new KindHandler() {
            @Override public String getName() {
                return "broken";
            }

            @Override public boolean detects(String content) {
                throw new IllegalStateException("boom");
            }

            @Override public int detectionPriority() {
                return 1;
            }
        };

        KindRegistry registry = registryOf(broken, handler("list", "- ", 20));

        assertThat(registry.detectKind("- a")).isEqualTo("list");
    }

    @Test
    void detectKind_nothingClaims_returnsNull() {
        assertThat(registryOf(() -> "text").detectKind("just prose")).isNull();
    }

    @Test
    void detectKind_blankContent_returnsNull() {
        assertThat(registryOf(handler("list", "", 20)).detectKind("   ")).isNull();
    }
}
