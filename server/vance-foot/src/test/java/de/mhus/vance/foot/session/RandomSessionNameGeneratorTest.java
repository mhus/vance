package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RandomSessionNameGeneratorTest {

    private final RandomSessionNameGenerator generator = new RandomSessionNameGenerator();

    @Test
    void generate_returnsNonBlank() {
        String name = generator.generate();
        assertThat(name).isNotNull();
        assertThat(name).isNotBlank();
    }

    @Test
    void generate_hasAdjectiveNounFormat() {
        String name = generator.generate();
        // Two parts separated by a single hyphen.
        String[] parts = name.split("-");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isNotBlank();
        assertThat(parts[1]).isNotBlank();
    }

    @Test
    void generate_isAllLowercase() {
        String name = generator.generate();
        assertThat(name).isEqualTo(name.toLowerCase());
    }

    @Test
    void generate_containsOnlyLettersAndHyphen() {
        String name = generator.generate();
        assertThat(name).matches("[a-z]+-[a-z]+");
    }

    @RepeatedTest(20)
    void repeatedGenerate_alwaysValidFormat() {
        String name = generator.generate();
        assertThat(name).matches("[a-z]+-[a-z]+");
    }

    @Test
    void generate_producesVarietyAcrossManyCalls() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            names.add(generator.generate());
        }
        // With 2500 combinations, 200 calls should produce far more than 1.
        // This is probabilistic, but the chance of getting only 1 unique name
        // in 200 draws from 2500 is astronomically small.
        assertThat(names.size()).isGreaterThan(1);
    }
}
