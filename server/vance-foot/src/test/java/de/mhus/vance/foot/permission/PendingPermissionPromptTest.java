package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PendingPermissionPromptTest {

    private final ChatTerminal terminal = mock(ChatTerminal.class);
    private final PendingPermissionPrompt prompt = new PendingPermissionPrompt(terminal);

    /** Spin until the prompt is waiting, or fail after ~2 s. */
    private void awaitActive() {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!prompt.hasActive()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("prompt never became active");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void offerAnswer_noActivePrompt_returnsFalse() {
        assertThat(prompt.offerAnswer("1")).isFalse();
        assertThat(prompt.hasActive()).isFalse();
    }

    @Test
    void await_userAnswers_returnsChoice() {
        CompletableFuture<PermissionChoice> result =
                CompletableFuture.supplyAsync(() -> prompt.await(() -> {}, 5_000));

        awaitActive();
        assertThat(prompt.offerAnswer("2")).isTrue();

        assertThat(result.join()).isEqualTo(PermissionChoice.ALLOW_ALWAYS);
        assertThat(prompt.hasActive()).isFalse();
    }

    @Test
    void await_timeout_returnsNull() {
        PermissionChoice choice = prompt.await(() -> {}, 120);

        assertThat(choice).isNull();
        assertThat(prompt.hasActive()).isFalse();
    }

    @Test
    void offerAnswer_invalidInput_consumedButKeepsWaiting() {
        CompletableFuture<PermissionChoice> result =
                CompletableFuture.supplyAsync(() -> prompt.await(() -> {}, 5_000));
        awaitActive();

        assertThat(prompt.offerAnswer("9")).isTrue();   // out of range — consumed, re-prompt
        assertThat(prompt.hasActive()).isTrue();         // still waiting
        assertThat(prompt.offerAnswer("nonsense")).isTrue();
        assertThat(prompt.hasActive()).isTrue();

        assertThat(prompt.offerAnswer("3")).isTrue();    // finally a valid answer
        assertThat(result.join()).isEqualTo(PermissionChoice.DENY_ONCE);
    }

    @Test
    void offerAnswer_blankLine_passesThrough() {
        CompletableFuture<PermissionChoice> result =
                CompletableFuture.supplyAsync(() -> prompt.await(() -> {}, 5_000));
        awaitActive();

        assertThat(prompt.offerAnswer("   ")).isFalse(); // blank → not consumed
        assertThat(prompt.hasActive()).isTrue();

        prompt.offerAnswer("1");
        assertThat(result.join()).isEqualTo(PermissionChoice.ALLOW_ONCE);
    }
}
