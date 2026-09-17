package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.llmtrace.LlmTraceDirection;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tool-surface fields of the trace: the provider serializes the
 * {@code tools} array ahead of every message, so a surface that moves
 * between turns of one process busts the prompt-cache prefix. Count and
 * estimated size ride on the first row of every round-trip — the drift
 * signature the planning doc works from
 * ({@code planning/tool-surface-stability.md}).
 */
class LlmTraceRecorderTest {

    @Test
    void theFirstRowOfATurnCarriesTheToolSurfaceAndOnlyThatRow() {
        List<LlmTraceDocument> rows = new ArrayList<>();
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("sys"), UserMessage.from("hi"))
                .toolSpecifications(ToolSpecification.builder()
                        .name("tool_a")
                        .description("does a")
                        .build())
                .build();

        LlmTraceRecorder.record(
                recordingService(rows),
                process(),
                "arthur",
                request,
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
                42L);

        assertThat(rows).hasSize(3);
        LlmTraceDocument first = rows.get(0);
        assertThat(first.getDirection()).isEqualTo(LlmTraceDirection.INPUT);
        assertThat(first.getToolsCount()).isEqualTo(1);
        assertThat(first.getToolsBytes()).isEqualTo(LlmTraceRecorder.estimateToolsBytes(request.toolSpecifications()));
        // Only the first row — the others stay null so a per-process
        // query does not triple-count.
        assertThat(rows.get(1).getToolsCount()).isNull();
        assertThat(rows.get(2).getToolsCount()).isNull();
    }

    @Test
    void aRequestWithoutToolsLeavesTheSurfaceFieldsNull() {
        List<LlmTraceDocument> rows = new ArrayList<>();
        ChatRequest request =
                ChatRequest.builder().messages(UserMessage.from("hi")).build();

        LlmTraceRecorder.record(
                recordingService(rows),
                process(),
                "arthur",
                request,
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
                42L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getToolsCount()).isNull();
        assertThat(rows.get(0).getToolsBytes()).isNull();
    }

    @Test
    void equalSurfacesProduceEqualByteEstimates() {
        // The estimate must be a deterministic function of the surface —
        // its whole point is that equal surfaces read equal, so drift is
        // visible as a change, not as noise.
        List<ToolSpecification> a =
                List.of(ToolSpecification.builder().name("t").description("d").build());
        List<ToolSpecification> b =
                List.of(ToolSpecification.builder().name("t").description("d").build());
        List<ToolSpecification> different =
                List.of(ToolSpecification.builder().name("t").description("d2").build());

        assertThat(LlmTraceRecorder.estimateToolsBytes(a)).isEqualTo(LlmTraceRecorder.estimateToolsBytes(b));
        assertThat(LlmTraceRecorder.estimateToolsBytes(a)).isNotEqualTo(LlmTraceRecorder.estimateToolsBytes(different));
    }

    // ──────────────────── helpers ────────────────────

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-1");
        p.setTenantId("acme");
        p.setSessionId("sess-1");
        return p;
    }

    /** Captures every recorded row — {@link LlmTraceService} is a class, so mock it. */
    private static LlmTraceService recordingService(List<LlmTraceDocument> sink) {
        LlmTraceService service = mock(LlmTraceService.class);
        when(service.record(any())).thenAnswer(inv -> {
            LlmTraceDocument doc = inv.getArgument(0);
            sink.add(doc);
            return doc;
        });
        return service;
    }
}
