package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClusterNodeNameGeneratorTest {

    private ClusterNodeNameGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ClusterNodeNameGenerator();
        generator.load();
    }

    @Test
    void load_dictionaryHasComfortablyManyEntries() {
        // The collision-resistance argument in the class doc only holds with
        // a "large enough" dictionary. The hard floor (32) is enforced in
        // load(); this asserts the bundled file is well above that.
        assertThat(generator.dictionarySize()).isGreaterThanOrEqualTo(200);
    }

    @Test
    void generate_alwaysReturnsTwoHyphenJoinedSegments() {
        for (int i = 0; i < 50; i++) {
            String name = generator.generate();
            assertThat(name).matches("^[a-z]+-[a-z]+$");
        }
    }

    @Test
    void generate_acrossManyCalls_producesMostlyDistinctNames() {
        // With ~350 entries the expected duplicate rate over 200 picks is
        // well under 30%. We assert "more than half are unique" as a
        // conservative collision-budget check that won't flap.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSizeGreaterThan(100);
    }
}
