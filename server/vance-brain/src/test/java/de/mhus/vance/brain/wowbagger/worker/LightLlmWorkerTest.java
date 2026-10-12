package de.mhus.vance.brain.wowbagger.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.light.LightLlmJsonAnswer;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.LightLlmTextAnswer;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The original worker behind the SPI: same prompt, same request, lines split, usage counted. */
class LightLlmWorkerTest {

    @Test
    void one_call_per_chunk_with_task_and_records_and_usage_counted() {
        LightLlmService llm = mock(LightLlmService.class);
        AiModelResolver models = mock(AiModelResolver.class);
        when(models.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("openai", "cortecs", "m", false));
        when(llm.callWithUsage(any(LightLlmRequest.class)))
                .thenReturn(new LightLlmTextAnswer("A\n\n  B  \n", "m", new LightLlmJsonAnswer.Usage(100, 7)));
        ResolvedRecipe recipe = mock(ResolvedRecipe.class);
        when(recipe.params()).thenReturn(Map.of("model", "default:fast"));
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("p1");

        PreparedWorker worker = new LightLlmWorker(llm, models)
                .prepare(new WorkerSetup(
                        process, new WorkerSpec("wowbagger-worker", "upper-case", 512, false, false), recipe));
        WorkerReply reply = worker.process(List.of("a", "b"), new ChunkContext(3, 6));

        assertThat(reply.lines()).containsExactly("A", "B");
        assertThat(reply.calls()).isEqualTo(1);
        assertThat(reply.tokensIn()).isEqualTo(100);
        assertThat(reply.tokensOut()).isEqualTo(7);
        ArgumentCaptor<LightLlmRequest> request = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(llm).callWithUsage(request.capture());
        assertThat(request.getValue().getRecipeName()).isEqualTo("wowbagger-worker");
        assertThat(request.getValue().getMaxTokens()).isEqualTo(512);
        assertThat(request.getValue().getProcessId()).isEqualTo("proc-1");
        assertThat(request.getValue().getUserPrompt())
                .startsWith("## Task\nupper-case")
                .contains("## Records (2 total, one per line, in order)\na\nb");
    }
}
