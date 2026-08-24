package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ActiveInboxContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The inbox hint on the way into the prompt. Pebble renders an unset variable as
 * the empty string in lenient mode, so a half-set hint would produce a context
 * block claiming the reader is looking at {@code ''} — which is worse than no
 * block at all.
 */
class PromptContextBuilderActiveInboxTest {

    @Test
    @SuppressWarnings("unchecked")
    void activeInbox_threadAndMessage_bothReachTheTemplate() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .activeInbox(ActiveInboxContext.builder()
                        .threadId("t1").messageId("m7").build())
                .build();

        Map<String, Object> view = (Map<String, Object>) ctx.get("activeInbox");
        assertThat(view).containsEntry("threadId", "t1").containsEntry("messageId", "m7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeInbox_threadOnly_omitsTheMessageKey() {
        // Nothing picked. The key must be absent rather than empty, so
        // {% if activeInbox.messageId %} in the template falls away.
        Map<String, Object> ctx = PromptContextBuilder.create()
                .activeInbox(ActiveInboxContext.builder().threadId("t1").build())
                .build();

        Map<String, Object> view = (Map<String, Object>) ctx.get("activeInbox");
        assertThat(view).containsEntry("threadId", "t1").doesNotContainKey("messageId");
    }

    @Test
    void activeInbox_nullOrBlankThread_leavesTheVariableUnset() {
        assertThat(PromptContextBuilder.create().activeInbox(null).build())
                .doesNotContainKey("activeInbox");

        // A blank thread id is the shape a buggy client would send; rendering
        // "thread ``" would tell the model something false.
        assertThat(PromptContextBuilder.create()
                .activeInbox(ActiveInboxContext.builder().threadId("  ").build())
                .build())
                .doesNotContainKey("activeInbox");
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeInbox_blankMessageId_isTreatedAsNothingPicked() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .activeInbox(ActiveInboxContext.builder()
                        .threadId("t1").messageId("").build())
                .build();

        Map<String, Object> view = (Map<String, Object>) ctx.get("activeInbox");
        assertThat(view).doesNotContainKey("messageId");
    }
}
