package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Builders for findings and registers shared by the Wonko unit tests. */
final class WonkoTestData {

    private WonkoTestData() {}

    static WonkoFinding finding(
            String key, QuestionWeight weight, @Nullable String category, @Nullable String proposal) {
        return new WonkoFinding(
                key,
                "Title " + key,
                category,
                "doc#section",
                "gap",
                weight,
                "What about " + key + "?",
                proposal == null ? QuestionKind.TEXT : QuestionKind.CONFIRM,
                List.of(),
                proposal,
                null,
                "quote",
                false,
                "because",
                null,
                null);
    }

    static WonkoFinding dependent(String key, String root) {
        return new WonkoFinding(
                key,
                "Title " + key,
                null,
                null,
                "gap",
                QuestionWeight.IMPORTANT,
                "What about " + key + "?",
                QuestionKind.TEXT,
                List.of(),
                null,
                root,
                null,
                false,
                "because",
                null,
                null);
    }

    static WonkoFinding contradiction(String key) {
        return new WonkoFinding(
                key,
                "Title " + key,
                null,
                null,
                "contradiction",
                QuestionWeight.IMPORTANT,
                "Which one holds for " + key + "?",
                QuestionKind.TEXT,
                List.of(),
                null,
                null,
                null,
                false,
                "because",
                null,
                null);
    }

    static WonkoTopic topic(WonkoRegister register, String key, WonkoTopicStatus status) {
        WonkoTopic t = new WonkoTopic();
        t.setKey(key);
        t.setTitle("Title " + key);
        t.setStatus(status);
        register.getTopics().add(t);
        return t;
    }

    static WonkoRegister register(MandateLevel defaultLevel) {
        WonkoRegister r = new WonkoRegister();
        r.setSubjectKey("documents:spec.md");
        r.setSubjectRefs(new java.util.ArrayList<>(List.of("spec.md")));
        r.getMandate().setDefaultLevel(defaultLevel);
        return r;
    }
}
