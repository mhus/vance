package de.mhus.vance.brain.suggestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.shared.permission.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestionServiceTest {

    private static SuggestionSource source(String name, int hits) {
        return new SuggestionSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<FormSuggestionDto> suggest(SuggestionRequest r) {
                return java.util.stream.IntStream.range(0, hits)
                        .mapToObj(i -> new FormSuggestionDto("x" + i, null, List.of("x" + i), null, null))
                        .toList();
            }
        };
    }

    private static SuggestionRequest request(String q, int limit) {
        return new SuggestionRequest("acme", SecurityContext.SYSTEM, null, null, q, limit);
    }

    @Test
    void unknownSourceAndBlankQueryAnswerNothing() {
        SuggestionService service = new SuggestionService(List.of(source("a", 3)));
        assertThat(service.suggest("nope", request("x", 8))).isEmpty();
        assertThat(service.suggest("a", request(" ", 8))).isEmpty();
    }

    @Test
    void limitIsEnforcedEvenWhenTheSourceIgnoresIt() {
        SuggestionService service = new SuggestionService(List.of(source("a", 30)));
        assertThat(service.suggest("a", request("x", 5))).hasSize(5);
    }

    @Test
    void duplicateNamesFailAtBoot() {
        assertThatThrownBy(() -> new SuggestionService(List.of(source("a", 1), source("a", 1))))
                .hasMessageContaining("Two suggestion sources named 'a'");
    }
}
