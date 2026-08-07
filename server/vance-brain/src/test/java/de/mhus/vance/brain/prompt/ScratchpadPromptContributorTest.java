package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.SystemBlockKind;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.ScratchpadService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScratchpadPromptContributorTest {

    private ScratchpadService scratchpadService;
    private ScratchpadPromptContributor contributor;
    private List<ChatMessage> messages;

    @BeforeEach
    void setUp() {
        scratchpadService = mock(ScratchpadService.class);
        contributor = new ScratchpadPromptContributor(scratchpadService);
        messages = new ArrayList<>();
    }

    private static ThinkProcessDocument process() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        return process;
    }

    @Test
    void append_withSlots_addsDynamicBlockSoItStaysOutsideThePromptCache() {
        when(scratchpadService.list("acme", "proc-1")).thenReturn(List.of(
                MemoryDocument.builder()
                        .kind(MemoryKind.SCRATCHPAD)
                        .title("todo")
                        .content("rebuild brain")
                        .build()));

        contributor.appendDynamicMessage(messages, process());

        assertThat(messages).hasSize(1);
        VanceSystemMessage block = (VanceSystemMessage) messages.get(0);
        assertThat(block.kind()).isEqualTo(SystemBlockKind.DYNAMIC);
        assertThat(block.text()).contains("## Scratchpad").contains("rebuild brain");
    }

    @Test
    void append_noSlots_addsNothing() {
        when(scratchpadService.list("acme", "proc-1")).thenReturn(List.of());

        contributor.appendDynamicMessage(messages, process());

        assertThat(messages).isEmpty();
    }

    @Test
    void append_unsavedProcess_doesNotQuery() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setTenantId("acme");

        contributor.appendDynamicMessage(messages, process);

        assertThat(messages).isEmpty();
        verify(scratchpadService, never()).list(any(), any());
    }
}
