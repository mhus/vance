package de.mhus.vance.brain.wonko;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What Wonko may decide on its own, per category (plan §6). Categories not
 * listed fall back to {@link #defaultLevel}.
 */
@Data
@NoArgsConstructor
public class WonkoMandate {

    private MandateLevel defaultLevel = MandateLevel.CONFIRM;

    private Map<String, WonkoCategory> categories = new LinkedHashMap<>();

    /** Free-text context that goes into every check prompt. */
    private @Nullable String notes;

    public MandateLevel levelFor(@Nullable String category) {
        if (category == null) {
            return defaultLevel;
        }
        WonkoCategory c = categories.get(category);
        return c == null ? defaultLevel : c.getLevel();
    }
}
