package de.mhus.vance.foot.question;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuestionsTextTest {

    @Test
    void replaceFences_rendersQuestionsReadable_keepsTheRest() {
        String content = "Please decide.\n```questions\nintro: Two points\nquestions:\n"
                + "  - key: r\n    text: Which region?\n    kind: choice\n    weight: blocker\n"
                + "    options: [{label: eu-central-1, description: Frankfurt}, eu-west-1]\n"
                + "    context: stored within the EU\n"
                + "  - key: t\n    text: 10 years?\n    kind: confirm\n    proposal: 10 years\n```\nThanks.";

        String out = QuestionsText.replaceFences(content);

        assertThat(out)
                .startsWith("Please decide.\n")
                .endsWith("Thanks.")
                .doesNotContain("```questions", "key: r")
                .contains(
                        "Two points",
                        "**1.** **[blocker]** Which region?",
                        "> stored within the EU",
                        "`[1]` eu-central-1 — Frankfurt   `[2]` eu-west-1",
                        "**2.** 10 years?",
                        "*Assumption:* 10 years");
    }

    @Test
    void replaceFences_leavesAnInvalidFenceAsItIs() {
        String content = "```questions\nquestions: []\n```\n";

        assertThat(QuestionsText.replaceFences(content)).isEqualTo(content);
    }
}
