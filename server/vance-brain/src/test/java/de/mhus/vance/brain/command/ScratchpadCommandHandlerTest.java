package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.ScratchpadService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScratchpadCommandHandlerTest {

    @Mock private ScratchpadService scratchpadService;

    private ScratchpadCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ScratchpadCommandHandler(scratchpadService);
    }

    private static ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").tenantId("acme").build();
    }

    private static EngineCommand cmd(String text) {
        return text.isEmpty()
                ? new EngineCommand("scratchpad", Map.of())
                : new EngineCommand("scratchpad", Map.of("text", text));
    }

    private static MemoryDocument slot(String title, String content) {
        return MemoryDocument.builder()
                .kind(MemoryKind.SCRATCHPAD)
                .title(title)
                .content(content)
                .build();
    }

    @Test
    void noSubcommand_defaultsToList() {
        when(scratchpadService.list("acme", "p1"))
                .thenReturn(List.of(slot("todo", "rebuild brain")));

        EngineCommandResult result = handler.handle(process(), cmd(""));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).isEqualTo("1 slot(s)");
        assertThat(String.valueOf(result.value())).contains("todo — 13 chars");
    }

    @Test
    void list_emptyInventory_saysSoWithoutDetail() {
        when(scratchpadService.list("acme", "p1")).thenReturn(List.of());

        EngineCommandResult result = handler.handle(process(), cmd("list"));

        assertThat(result.message()).isEqualTo("no slots");
        assertThat(result.value()).isNull();
    }

    @Test
    void get_returnsFullContentUncapped() {
        String content = "x".repeat(5_000);
        when(scratchpadService.get("acme", "p1", "findings"))
                .thenReturn(Optional.of(slot("findings", content)));

        EngineCommandResult result = handler.handle(process(), cmd("get findings"));

        assertThat(result.message()).isEqualTo("findings — 5000 chars");
        assertThat(String.valueOf(result.value())).contains(content);
    }

    @Test
    void get_unknownKey_isOkNotAnError() {
        when(scratchpadService.get("acme", "p1", "nope")).thenReturn(Optional.empty());

        EngineCommandResult result = handler.handle(process(), cmd("get nope"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).isEqualTo("nope (not set)");
    }

    @Test
    void get_withoutKey_reportsUsage() {
        EngineCommandResult result = handler.handle(process(), cmd("get"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("//scratchpad get <key>");
        verify(scratchpadService, never()).get(any(), any(), any());
    }

    @Test
    void delete_dropsTheSlot() {
        when(scratchpadService.delete("acme", "p1", "todo")).thenReturn(true);

        EngineCommandResult result = handler.handle(process(), cmd("delete todo"));

        assertThat(result.message()).isEqualTo("todo deleted");
        verify(scratchpadService).delete("acme", "p1", "todo");
    }

    @Test
    void delete_acceptsRmAlias() {
        when(scratchpadService.delete("acme", "p1", "todo")).thenReturn(false);

        EngineCommandResult result = handler.handle(process(), cmd("rm todo"));

        assertThat(result.message()).isEqualTo("todo (not set)");
    }

    @Test
    void block_showsWhatTheModelSees() {
        when(scratchpadService.list("acme", "p1"))
                .thenReturn(List.of(slot("todo", "rebuild brain")));

        EngineCommandResult result = handler.handle(process(), cmd("block"));

        assertThat(result.message()).contains("chars injected per turn");
        assertThat(String.valueOf(result.value()))
                .contains("## Scratchpad")
                .contains("- `todo`: rebuild brain");
    }

    @Test
    void block_emptyInventory_statesThatNothingIsInjected() {
        when(scratchpadService.list("acme", "p1")).thenReturn(List.of());

        EngineCommandResult result = handler.handle(process(), cmd("block"));

        assertThat(result.message()).isEqualTo("no block injected — inventory empty");
    }

    @Test
    void unknownSubcommand_listsTheValidOnes() {
        EngineCommandResult result = handler.handle(process(), cmd("frobnicate"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("list | get <key> | delete <key> | block");
    }

    @Test
    void unpersistedProcess_doesNotQuery() {
        EngineCommandResult result = handler.handle(
                ThinkProcessDocument.builder().tenantId("acme").build(), cmd("list"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verify(scratchpadService, never()).list(any(), any());
    }
}
