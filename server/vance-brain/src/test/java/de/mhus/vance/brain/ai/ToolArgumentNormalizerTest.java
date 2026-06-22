package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolArgumentNormalizerTest {

    // -- normalizeWithOutcome ------------------------------------------------

    @Test
    void normalize_cleanObject_isNoop() {
        ToolArgumentNormalizer.Result r =
                ToolArgumentNormalizer.normalizeWithOutcome("{\"k\":\"v\"}");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.NOOP);
        assertThat(r.args()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void normalize_emptyObject_isNoop() {
        ToolArgumentNormalizer.Result r = ToolArgumentNormalizer.normalizeWithOutcome("{}");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.NOOP);
        assertThat(r.args()).isEqualTo("{}");
    }

    @Test
    void normalize_objectFollowedByTrailingString_trimsTrailingGarbage() {
        // The DeepSeek-V4-Pro pattern: '{}' immediately followed by a
        // bare quoted string. Jackson reads the first complete value
        // and we discard the rest on re-serialise.
        ToolArgumentNormalizer.Result r =
                ToolArgumentNormalizer.normalizeWithOutcome("{} \"manual_list\"");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.TRIMMED);
        assertThat(r.args()).isEqualTo("{}");
    }

    @Test
    void normalize_objectWithPayloadFollowedByGarbage_keepsPayloadDropsGarbage() {
        ToolArgumentNormalizer.Result r =
                ToolArgumentNormalizer.normalizeWithOutcome("{\"name\":\"x\"} extra junk");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.TRIMMED);
        assertThat(r.args()).isEqualTo("{\"name\":\"x\"}");
    }

    @Test
    void normalize_emptyString_collapsesToEmptyObject() {
        ToolArgumentNormalizer.Result r = ToolArgumentNormalizer.normalizeWithOutcome("");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.EMPTIED);
        assertThat(r.args()).isEqualTo("{}");
    }

    @Test
    void normalize_null_collapsesToEmptyObject() {
        ToolArgumentNormalizer.Result r = ToolArgumentNormalizer.normalizeWithOutcome(null);
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.EMPTIED);
        assertThat(r.args()).isEqualTo("{}");
    }

    @Test
    void normalize_arrayInsteadOfObject_collapsesToEmptyObject() {
        ToolArgumentNormalizer.Result r = ToolArgumentNormalizer.normalizeWithOutcome("[1,2,3]");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.EMPTIED);
        assertThat(r.args()).isEqualTo("{}");
    }

    @Test
    void normalize_malformedJson_collapsesToEmptyObject() {
        ToolArgumentNormalizer.Result r =
                ToolArgumentNormalizer.normalizeWithOutcome("{not even close");
        assertThat(r.outcome()).isEqualTo(ToolArgumentNormalizer.Outcome.EMPTIED);
        assertThat(r.args()).isEqualTo("{}");
    }

    // -- normalize(AiMessage) ------------------------------------------------

    @Test
    void normalizeAiMessage_withoutToolCalls_returnsSameInstance() {
        AiMessage original = AiMessage.from("hello");
        AiMessage out = ToolArgumentNormalizer.normalize(original, "any", null);
        assertThat(out).isSameAs(original);
    }

    @Test
    void normalizeAiMessage_allCleanToolCalls_returnsSameInstance() {
        AiMessage original = AiMessage.from("ok", List.of(
                ToolExecutionRequest.builder()
                        .id("a").name("manual_list").arguments("{}").build()));
        AiMessage out = ToolArgumentNormalizer.normalize(original, "any", null);
        assertThat(out).isSameAs(original);
    }

    @Test
    void normalizeAiMessage_rewritesDirtyToolArgs_preservingIdAndName() {
        AiMessage original = AiMessage.from("explain", List.of(
                ToolExecutionRequest.builder()
                        .id("call_42").name("manual_list")
                        .arguments("{} \"manual_list\"").build()));
        AiMessage out = ToolArgumentNormalizer.normalize(original, "openai:deepseek-v4-pro", null);

        assertThat(out).isNotSameAs(original);
        assertThat(out.text()).isEqualTo("explain");
        assertThat(out.toolExecutionRequests()).hasSize(1);
        ToolExecutionRequest rebuilt = out.toolExecutionRequests().get(0);
        assertThat(rebuilt.id()).isEqualTo("call_42");
        assertThat(rebuilt.name()).isEqualTo("manual_list");
        assertThat(rebuilt.arguments()).isEqualTo("{}");
    }

    @Test
    void normalizeAiMessage_toolOnlyMessage_isRebuiltWithoutText() {
        AiMessage original = AiMessage.from(List.of(
                ToolExecutionRequest.builder()
                        .id("c1").name("whoami")
                        .arguments("").build()));
        AiMessage out = ToolArgumentNormalizer.normalize(original, "m", null);

        assertThat(out.toolExecutionRequests()).hasSize(1);
        assertThat(out.toolExecutionRequests().get(0).arguments()).isEqualTo("{}");
        assertThat(out.text()).isNull();
    }

    @Test
    void normalizeAiMessage_mixedCleanAndDirty_rebuildsOnlyTheDirtyOne() {
        ToolExecutionRequest clean = ToolExecutionRequest.builder()
                .id("c1").name("x").arguments("{\"a\":1}").build();
        ToolExecutionRequest dirty = ToolExecutionRequest.builder()
                .id("c2").name("y").arguments("{} trailing").build();
        AiMessage original = AiMessage.from("t", List.of(clean, dirty));

        AiMessage out = ToolArgumentNormalizer.normalize(original, "m", null);

        assertThat(out).isNotSameAs(original);
        assertThat(out.toolExecutionRequests().get(0)).isSameAs(clean);
        assertThat(out.toolExecutionRequests().get(1).arguments()).isEqualTo("{}");
        assertThat(out.toolExecutionRequests().get(1).id()).isEqualTo("c2");
    }
}
