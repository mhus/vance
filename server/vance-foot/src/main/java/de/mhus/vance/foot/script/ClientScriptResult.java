package de.mhus.vance.foot.script;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

public record ClientScriptResult(
        @Nullable Object value,
        Duration duration) {
}
