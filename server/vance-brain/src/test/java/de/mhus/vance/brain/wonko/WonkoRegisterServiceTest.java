package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WonkoRegisterServiceTest {

    private final MemoryService memoryService = mock(MemoryService.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final WonkoRegisterService service = new WonkoRegisterService(memoryService, objectMapper);

    @Test
    void subjectKey_isOrderIndependent() {
        assertThat(WonkoRegisterService.subjectKey(List.of("b.md", "a.md")))
                .isEqualTo(WonkoRegisterService.subjectKey(List.of("a.md", "b.md")))
                .isEqualTo("documents:a.md,b.md");
    }

    @Test
    void save_writesProjectScopedReviewEntry_andSupersedesThePrevious() {
        WonkoRegister register = WonkoTestData.register(MandateLevel.CONFIRM);
        WonkoTestData.topic(register, "retry", WonkoTopicStatus.SETTLED);
        MemoryDocument previous = MemoryDocument.builder().id("old").build();
        when(memoryService.activeProjectEntryByMetadata(
                        "acme", "p", MemoryKind.REVIEW, "subjectKey", "documents:spec.md"))
                .thenReturn(Optional.of(previous));
        when(memoryService.save(any())).thenAnswer(inv -> {
            MemoryDocument d = inv.getArgument(0);
            d.setId("new");
            return d;
        });

        service.save("acme", "p", register);

        ArgumentCaptor<MemoryDocument> saved = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(MemoryKind.REVIEW);
        assertThat(saved.getValue().getSessionId()).isNull();
        assertThat(saved.getValue().getThinkProcessId()).isNull();
        assertThat(saved.getValue().getMetadata()).containsEntry("subjectKey", "documents:spec.md");
        verify(memoryService).supersede(eq("old"), eq("new"));
    }

    @Test
    void loadOrCreate_roundTripsTheRegister() {
        WonkoRegister register = WonkoTestData.register(MandateLevel.ASK);
        WonkoTestData.topic(register, "retry", WonkoTopicStatus.SETTLED);
        MemoryDocument stored = MemoryDocument.builder()
                .content(objectMapper.writeValueAsString(register))
                .build();
        when(memoryService.activeProjectEntryByMetadata(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(stored));

        WonkoRegister loaded = service.loadOrCreate("acme", "p", "documents:spec.md", List.of("spec.md"));

        assertThat(loaded.getMandate().getDefaultLevel()).isEqualTo(MandateLevel.ASK);
        assertThat(loaded.topic("retry"))
                .get()
                .extracting(WonkoTopic::getStatus)
                .isEqualTo(WonkoTopicStatus.SETTLED);
    }
}
