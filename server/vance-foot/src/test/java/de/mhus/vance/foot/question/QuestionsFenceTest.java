package de.mhus.vance.foot.question;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuestionsFenceTest {

    private static final String BODY = "questions:\n  - key: a\n    text: A?\n";

    @Test
    void find_locatesClosedFences_ignoresOtherFencesAndAnOpenOne() {
        String content = "Intro\n```java\nint x;\n```\n```questions\n" + BODY + "```\ntail\n```questions\nopen";

        var found = QuestionsFence.find(content);

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.body()).isEqualTo(BODY);
            assertThat(content.substring(f.start(), f.end())).isEqualTo("```questions\n" + BODY + "```\n");
        });
    }

    @Test
    void parse_skipsAStoredMetaHeader() {
        var set = QuestionsFence.parse("$meta:\n  kind: questions\n" + BODY);

        assertThat(set.getQuestions())
                .singleElement()
                .satisfies(q -> assertThat(q.getText()).isEqualTo("A?"));
    }
}
