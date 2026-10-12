package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemQuestionDto;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A decision profile as read from a recipe with {@code engine: zem}:
 * which model, which questions, which part of the state to send. The
 * questions are parsed but not yet validated — validation needs the
 * resolved model's limits.
 *
 * @param name        recipe name the profile came from
 * @param model       model alias or {@code instance:model}, {@code null}
 *                    = the service default ({@code default:decision})
 * @param questions   in recipe order
 * @param stateSelect field paths the state is projected onto, empty = all
 */
public record ZemProfile(
        String name, @Nullable String model, List<ZemQuestionDto> questions, List<String> stateSelect) {

    public ZemProfile {
        questions = List.copyOf(questions);
        stateSelect = List.copyOf(stateSelect);
    }
}
