package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.SystemBlockKind;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.brain.prompt.PromptDateBlock.Granularity;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptDateBlockTest {

    private static Clock fixedAt(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    @Test
    void resolve_blankParam_returnsAutoForTier() {
        assertThat(PromptDateBlock.resolve(null, ModelSize.LARGE)).isEqualTo(Granularity.HOUR);
        assertThat(PromptDateBlock.resolve("", ModelSize.SMALL)).isEqualTo(Granularity.DAY);
        assertThat(PromptDateBlock.resolve("   ", null)).isEqualTo(Granularity.DAY);
    }

    @Test
    void resolve_explicitNone_returnsNone() {
        assertThat(PromptDateBlock.resolve("none", ModelSize.LARGE)).isEqualTo(Granularity.NONE);
        assertThat(PromptDateBlock.resolve("off", ModelSize.LARGE)).isEqualTo(Granularity.NONE);
        assertThat(PromptDateBlock.resolve("false", ModelSize.LARGE)).isEqualTo(Granularity.NONE);
    }

    @Test
    void resolve_autoOnSmall_returnsDay() {
        assertThat(PromptDateBlock.resolve("auto", ModelSize.SMALL)).isEqualTo(Granularity.DAY);
    }

    @Test
    void resolve_autoOnLarge_returnsHour() {
        assertThat(PromptDateBlock.resolve("auto", ModelSize.LARGE)).isEqualTo(Granularity.HOUR);
    }

    @Test
    void resolve_autoWithoutTier_returnsDay() {
        assertThat(PromptDateBlock.resolve("auto", null)).isEqualTo(Granularity.DAY);
    }

    @Test
    void resolve_explicitDay_returnsDay() {
        assertThat(PromptDateBlock.resolve("day", ModelSize.LARGE)).isEqualTo(Granularity.DAY);
        assertThat(PromptDateBlock.resolve("date", ModelSize.LARGE)).isEqualTo(Granularity.DAY);
    }

    @Test
    void resolve_explicitHour_returnsHour() {
        assertThat(PromptDateBlock.resolve("hour", ModelSize.SMALL)).isEqualTo(Granularity.HOUR);
    }

    @Test
    void resolve_unknownValue_fallsBackToAutoForTier() {
        assertThat(PromptDateBlock.resolve("weekly", ModelSize.LARGE)).isEqualTo(Granularity.HOUR);
        assertThat(PromptDateBlock.resolve("weekly", ModelSize.SMALL)).isEqualTo(Granularity.DAY);
    }

    @Test
    void render_none_returnsEmpty() {
        assertThat(PromptDateBlock.render(Granularity.NONE, fixedAt("2026-06-24T14:35:00Z"))).isEmpty();
    }

    @Test
    void render_day_emitsIsoDateOnly() {
        assertThat(PromptDateBlock.render(Granularity.DAY, fixedAt("2026-06-24T14:35:00Z")))
                .isEqualTo("Current date: 2026-06-24");
    }

    @Test
    void render_hour_emitsDateWithHourAndZone() {
        assertThat(PromptDateBlock.render(Granularity.HOUR, fixedAt("2026-06-24T14:35:00Z")))
                .isEqualTo("Current date: 2026-06-24 14h UTC");
    }

    @Test
    void render_hour_padsSingleDigitHour() {
        assertThat(PromptDateBlock.render(Granularity.HOUR, fixedAt("2026-06-24T03:35:00Z")))
                .isEqualTo("Current date: 2026-06-24 03h UTC");
    }

    @Test
    void render_hour_emitsOffsetForNonUtcZone() {
        Clock berlinClock = Clock.fixed(
                Instant.parse("2026-06-24T12:35:00Z"),
                java.time.ZoneId.of("Europe/Berlin"));
        assertThat(PromptDateBlock.render(Granularity.HOUR, berlinClock))
                .isEqualTo("Current date: 2026-06-24 14h +02:00");
    }

    @Test
    void appendDynamicMessage_nullParams_appliesAutoDefault() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getEngineParams()).thenReturn(null);
        List<ChatMessage> messages = new ArrayList<>();

        PromptDateBlock.appendDynamicMessage(messages, process, ModelSize.LARGE);

        assertThat(messages).hasSize(1);
        VanceSystemMessage block = (VanceSystemMessage) messages.get(0);
        assertThat(block.kind()).isEqualTo(SystemBlockKind.DYNAMIC);
        assertThat(block.text()).startsWith("Current date: ").contains("h");
    }

    @Test
    void appendDynamicMessage_paramMissing_appliesAutoDefault() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getEngineParams()).thenReturn(Map.of("other", "value"));
        List<ChatMessage> messages = new ArrayList<>();

        PromptDateBlock.appendDynamicMessage(messages, process, ModelSize.SMALL);

        assertThat(messages).hasSize(1);
        VanceSystemMessage block = (VanceSystemMessage) messages.get(0);
        assertThat(block.text()).startsWith("Current date: ").doesNotContain("h");
    }

    @Test
    void appendDynamicMessage_paramNone_isNoOp() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getEngineParams()).thenReturn(Map.of("promptDateGranularity", "none"));
        List<ChatMessage> messages = new ArrayList<>();

        PromptDateBlock.appendDynamicMessage(messages, process, ModelSize.LARGE);

        assertThat(messages).isEmpty();
    }

    @Test
    void appendDynamicMessage_autoOnLarge_addsHourDynamicBlock() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getEngineParams()).thenReturn(Map.of("promptDateGranularity", "auto"));
        List<ChatMessage> messages = new ArrayList<>();

        PromptDateBlock.appendDynamicMessage(messages, process, ModelSize.LARGE);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(VanceSystemMessage.class);
        VanceSystemMessage block = (VanceSystemMessage) messages.get(0);
        assertThat(block.kind()).isEqualTo(SystemBlockKind.DYNAMIC);
        assertThat(block.text()).startsWith("Current date: ").contains("h");
    }

    @Test
    void appendDynamicMessage_autoOnSmall_addsDayDynamicBlock() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getEngineParams()).thenReturn(Map.of("promptDateGranularity", "auto"));
        List<ChatMessage> messages = new ArrayList<>();

        PromptDateBlock.appendDynamicMessage(messages, process, ModelSize.SMALL);

        assertThat(messages).hasSize(1);
        VanceSystemMessage block = (VanceSystemMessage) messages.get(0);
        assertThat(block.kind()).isEqualTo(SystemBlockKind.DYNAMIC);
        assertThat(block.text()).startsWith("Current date: ").doesNotContain("h");
    }
}
