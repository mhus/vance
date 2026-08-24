package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * A parsed event handler.
 *
 * <p>Written in the view document as a single string:
 *
 * <pre>
 * on:
 *   click: "navigate:edit"
 *   rowClick: "scripts/main.js:openInvoice"
 * </pre>
 *
 * <p><b>The separator is {@code :} and not {@code #}.</b> A script handler
 * looks like a document reference with a function name appended, and the
 * obvious spelling would be {@code scripts/main.js#openInvoice} — but
 * {@code DocumentRefResolver} discards the fragment of a {@code vance:}
 * reference, so the function name would vanish without a word. The whole
 * handler is therefore parsed here, and the reference half is resolved
 * against the app folder like every other authored reference.
 *
 * @param kind      what this handler does.
 * @param target    {@link ActionKind#NAVIGATE}: the view handle to open.
 * @param scriptRef {@link ActionKind#SCRIPT}: the script document, as
 *                  authored (relative to the app folder).
 * @param function  {@link ActionKind#SCRIPT}: the exported function name.
 * @param raw       the handler string as written, for error messages.
 */
@GenerateTypeScript("bistromath")
public record ViewAction(
        ActionKind kind,
        @Nullable String target,
        @Nullable String scriptRef,
        @Nullable String function,
        String raw) {

    public static ViewAction navigate(String handle, String raw) {
        return new ViewAction(ActionKind.NAVIGATE, handle, null, null, raw);
    }

    public static ViewAction reload(String raw) {
        return new ViewAction(ActionKind.RELOAD, null, null, null, raw);
    }

    public static ViewAction script(String ref, String function, String raw) {
        return new ViewAction(ActionKind.SCRIPT, null, ref, function, raw);
    }
}
