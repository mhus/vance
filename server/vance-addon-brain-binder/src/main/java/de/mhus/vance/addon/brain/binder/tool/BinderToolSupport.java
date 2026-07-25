package de.mhus.vance.addon.brain.binder.tool;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Shared param helpers for the {@code binder_*} tool family. */
final class BinderToolSupport {

    private BinderToolSupport() {}

    static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
