package de.mhus.vance.shared.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.project.ProjectDocument;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Eligibility rules — the single reader of labels, selector and exclusive. */
class PodSelectorTest {

    // ─── the empty-selector default and its inversion ────────────────

    @Test
    void emptySelector_matchesAnyOrdinaryPod() {
        assertThat(PodSelector.matches(Map.of(), Map.of("gpu", "true"), false)).isTrue();
        assertThat(PodSelector.matches(Map.of(), Map.of(), false)).isTrue();
    }

    @Test
    void nullSelectorAndNullLabels_behaveLikeEmpty() {
        // Documents written before these fields existed deserialise as null.
        // "No labels" and "an empty label map" must be the same state — that is
        // what makes this an additive change with no migration behind it.
        assertThat(PodSelector.matches(null, null, false)).isTrue();
        assertThat(PodSelector.matches(null, Map.of("gpu", "true"), false)).isTrue();
        assertThat(PodSelector.matches(Map.of("gpu", "true"), null, false)).isFalse();
    }

    @Test
    void exclusivePod_refusesSelectorlessProject() {
        assertThat(PodSelector.matches(Map.of(), Map.of("gpu", "true"), true))
                .as("that is the whole point: ordinary work must not fill a special pod")
                .isFalse();
    }

    @Test
    void exclusivePod_stillAcceptsAMatchingSelector() {
        assertThat(PodSelector.matches(Map.of("gpu", "true"), Map.of("gpu", "true"), true))
                .isTrue();
    }

    @Test
    void exclusiveWithoutLabels_isAFullCordon() {
        // Nothing matches: an empty selector is refused by exclusive, and a
        // non-empty one finds no labels. This is why there is no cordoned flag.
        assertThat(PodSelector.matches(Map.of(), Map.of(), true)).isFalse();
        assertThat(PodSelector.matches(Map.of("gpu", "true"), Map.of(), true)).isFalse();
    }

    // ─── matching ───────────────────────────────────────────────────

    @Test
    void everySelectorEntryMustMatch() {
        Map<String, String> podLabels = Map.of("gpu", "true", "region", "eu");

        assertThat(PodSelector.matches(Map.of("gpu", "true"), podLabels, false)).isTrue();
        assertThat(PodSelector.matches(
                Map.of("gpu", "true", "region", "eu"), podLabels, false)).isTrue();
        assertThat(PodSelector.matches(
                Map.of("gpu", "true", "region", "us"), podLabels, false)).isFalse();
        assertThat(PodSelector.matches(
                Map.of("gpu", "true", "fast", "yes"), podLabels, false)).isFalse();
    }

    @Test
    void podMayCarryLabelsTheSelectorDoesNotAskAbout() {
        assertThat(PodSelector.matches(
                Map.of("gpu", "true"), Map.of("gpu", "true", "region", "eu"), false)).isTrue();
    }

    @Test
    void valueComparisonIsExact() {
        assertThat(PodSelector.matches(Map.of("gpu", "true"), Map.of("gpu", "True"), false))
                .isFalse();
    }

    @Test
    void isEligible_readsBothDocuments() {
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme").name("p1")
                .placementSelector(new HashMap<>(Map.of("gpu", "true")))
                .build();
        BrainPodDocument matching = BrainPodDocument.builder()
                .nodeName("a").labels(new HashMap<>(Map.of("gpu", "true"))).build();
        BrainPodDocument plain = BrainPodDocument.builder().nodeName("b").build();

        assertThat(PodSelector.isEligible(project, matching)).isTrue();
        assertThat(PodSelector.isEligible(project, plain)).isFalse();
    }

    // ─── grammar ────────────────────────────────────────────────────

    @Test
    void validate_rejectsDotInKeyBecauseMongoReadsItAsAPath() {
        assertThatThrownBy(() -> PodSelector.validate(Map.of("eu.region", "x")))
                .isInstanceOf(PodSelector.InvalidLabelException.class)
                .hasMessageContaining("eu.region");
    }

    @Test
    void validate_acceptsDotInValue() {
        // Values are never map keys, so a version or a region code is fine.
        PodSelector.validate(Map.of("version", "1.2.3", "region", "eu-central-1"));
    }

    @Test
    void validate_rejectsBlankValue() {
        assertThatThrownBy(() -> PodSelector.validate(Map.of("gpu", "")))
                .isInstanceOf(PodSelector.InvalidLabelException.class);
    }

    @Test
    void validate_nullMapIsFine() {
        PodSelector.validate(null);
    }
}
