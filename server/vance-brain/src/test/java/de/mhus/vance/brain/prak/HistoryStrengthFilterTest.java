package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.prak.SpanStrength;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HistoryStrengthFilterTest {

    private PrakProperties props;
    private HistoryStrengthFilter filter;

    @BeforeEach
    void setUp() {
        props = new PrakProperties();
        filter = new HistoryStrengthFilter(props);
    }

    // ─── filter ───

    @Test
    void filter_disabledByDefault_returnsInputUnchanged() {
        List<ChatMessageDocument> in = List.of(
                msg("m1", strength(SpanStrength.WEAK)),
                msg("m2", strength(SpanStrength.NORMAL)));

        List<ChatMessageDocument> out = filter.filter(in);

        assertThat(out).isSameAs(in);
    }

    @Test
    void filter_enabledDefaultThresholdDropsWeakKeepsRest() {
        props.setContextFilterEnabled(true);
        // default threshold: NORMAL → drop only WEAK
        List<ChatMessageDocument> in = List.of(
                msg("m1", strength(SpanStrength.WEAK)),
                msg("m2", strength(SpanStrength.NORMAL)),
                msg("m3", strength(SpanStrength.STRONG)),
                msg("m4", strength(SpanStrength.PINNED)),
                msg("m5", noTag()));      // untagged → defaults to NORMAL

        List<ChatMessageDocument> out = filter.filter(in);

        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("m2", "m3", "m4", "m5");
    }

    @Test
    void filter_strongThresholdAlsoDropsNormalAndUntagged() {
        props.setContextFilterEnabled(true);
        props.setContextFilterMinStrength(SpanStrength.STRONG);
        List<ChatMessageDocument> in = List.of(
                msg("m1", strength(SpanStrength.WEAK)),
                msg("m2", strength(SpanStrength.NORMAL)),
                msg("m3", strength(SpanStrength.STRONG)),
                msg("m4", strength(SpanStrength.PINNED)),
                msg("m5", noTag()));

        List<ChatMessageDocument> out = filter.filter(in);

        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("m3", "m4");
    }

    @Test
    void filter_weakThresholdKeepsEverythingFastPath() {
        props.setContextFilterEnabled(true);
        props.setContextFilterMinStrength(SpanStrength.WEAK);

        List<ChatMessageDocument> in = List.of(
                msg("m1", strength(SpanStrength.WEAK)),
                msg("m2", strength(SpanStrength.NORMAL)));

        List<ChatMessageDocument> out = filter.filter(in);

        // weak threshold ⇒ fast path returns the same list reference
        assertThat(out).isSameAs(in);
    }

    @Test
    void filter_emptyHistoryReturnsEmpty() {
        props.setContextFilterEnabled(true);
        assertThat(filter.filter(List.of())).isEmpty();
    }

    @Test
    void filter_nullHistoryReturnsEmpty() {
        props.setContextFilterEnabled(true);
        assertThat(filter.filter(null)).isEmpty();
    }

    @Test
    void filter_ignoresUnrelatedTags() {
        props.setContextFilterEnabled(true);
        // Tag set has only non-STRENGTH tags → message treated as NORMAL → kept.
        ChatMessageDocument m = msg("m1", Set.of("FILE_EDIT", "TOOL_CALL:read_file"));
        assertThat(filter.filter(List.of(m))).hasSize(1);
    }

    // ─── strengthOf ───

    @Test
    void strengthOf_readsTagWhenPresent() {
        ChatMessageDocument m = msg("m1", strength(SpanStrength.STRONG));
        assertThat(HistoryStrengthFilter.strengthOf(m)).isEqualTo(SpanStrength.STRONG);
    }

    @Test
    void strengthOf_defaultsToNormalWhenNoTag() {
        ChatMessageDocument m = msg("m1", null);
        assertThat(HistoryStrengthFilter.strengthOf(m)).isEqualTo(SpanStrength.NORMAL);
    }

    @Test
    void strengthOf_defaultsToNormalWhenOnlyUnrelatedTags() {
        ChatMessageDocument m = msg("m1", Set.of("FILE_EDIT", "TOOL_CALL:x"));
        assertThat(HistoryStrengthFilter.strengthOf(m)).isEqualTo(SpanStrength.NORMAL);
    }

    // ─── factories ───

    private static ChatMessageDocument msg(String id, Set<String> tags) {
        ChatMessageDocument m = ChatMessageDocument.builder()
                .id(id)
                .tenantId("t").sessionId("s").thinkProcessId("p")
                .role(ChatRole.USER)
                .content("content " + id)
                .tags(tags == null ? null : new LinkedHashSet<>(tags))
                .build();
        return m;
    }

    private static Set<String> strength(SpanStrength s) {
        return Set.of(s.tag());
    }

    private static Set<String> noTag() {
        return Set.of();
    }
}
