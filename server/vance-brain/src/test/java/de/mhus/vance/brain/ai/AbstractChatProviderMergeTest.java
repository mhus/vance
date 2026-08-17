package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Set;
import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * {@link AbstractChatProvider#mergeSystemMessages} — the request-shape
 * workaround has to sit in the template, because the renderer that needs
 * it ({@code glimmer}) is reachable through the local Ollama API, through
 * Ollama Cloud and through any OpenAI-compatible gateway. Wired into one
 * provider it would silently miss the other two.
 */
class AbstractChatProviderMergeTest {

    @Test
    void modelWithoutTheFlag_isPassedThroughUnwrapped() {
        ChatModel sync = mock(ChatModel.class);
        StreamingChatModel streaming = mock(StreamingChatModel.class);

        AbstractChatProvider.BuiltChat out = AbstractChatProvider.mergeSystemMessages(
                new AbstractChatProvider.BuiltChat(sync, streaming), model(false));

        assertThat(out.sync()).isSameAs(sync);
        assertThat(out.streaming()).isSameAs(streaming);
    }

    @Test
    void modelWithTheFlag_wrapsBothSyncAndStreaming() {
        // Chat turns run through the streaming model, so a merge that only
        // covered the sync path would leave production traffic untouched.
        AbstractChatProvider.BuiltChat out = AbstractChatProvider.mergeSystemMessages(
                new AbstractChatProvider.BuiltChat(
                        mock(ChatModel.class), mock(StreamingChatModel.class)),
                model(true));

        assertThat(out.sync()).isInstanceOf(SystemMessageMergingChatModel.class);
        assertThat(out.streaming()).isInstanceOf(SystemMessageMergingStreamingChatModel.class);
    }

    @Test
    void modelWithTheFlag_andNoStreamingModel_staysNull() {
        // A backend may expose only a sync model; wrapping null would
        // turn a supported shape into an NPE at the first turn.
        AbstractChatProvider.BuiltChat out = AbstractChatProvider.mergeSystemMessages(
                new AbstractChatProvider.BuiltChat(mock(ChatModel.class), null), model(true));

        assertThat(out.sync()).isInstanceOf(SystemMessageMergingChatModel.class);
        assertThat(out.streaming()).isNull();
    }

    private static ModelInfo model(boolean mergeSystemMessages) {
        return new ModelInfo("ollama", "muse-glimmer:30b-mlx", 131_072, 8192,
                ModelSize.SMALL,
                Set.of(),
                ModelInfo.DEFAULT_TIMEOUT_SECONDS,
                ModelInfo.DEFAULT_ACTION_LOOP_CORRECTIONS,
                false,
                /*messageParser*/ null,
                /*pricing*/ null,
                OutputTokenParam.MAX_TOKENS,
                /*unsupportedParams*/ Set.of(),
                /*reasoningEffortWhenOff*/ null,
                /*maxTools*/ null,
                mergeSystemMessages);
    }
}
