package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.ursahooks.UrsaHookSource;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-hook} kind — one event hook of
 * the UrsaHooks system ({@code _vance/hooks/<event>/<name>.yaml}, spec
 * {@code specification/ursahooks.md}). The event is a path segment, not a
 * body field: the loader lists per event and hands the wire name to the
 * parser, so the path <em>is</em> part of the hook's identity.
 *
 * <p><b>Validation delegates to the canonical parser.</b>
 * {@link UrsaHookLoader} skips a hook that does not parse — logged, never
 * fatal — so a broken hook is silently missing, the recipe-handler
 * situation again. Rather than duplicating the grammar here, the handler
 * runs {@link UrsaHookYamlParser#parse} with the event derived from the
 * path: a finding means exactly what the loader does, because it is the
 * same code that says it. The event segment itself is checked first — a
 * hook under an unknown event is invisible to the registry without a
 * single body problem ever being reached.
 */
@Service
public class HookDocKindHandler implements KindHandler {

    public static final String KIND = "vance-hook";

    private final UrsaHookYamlParser parser;

    public HookDocKindHandler(UrsaHookYamlParser parser) {
        this.parser = parser;
    }

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The hook tree is the marker: every document under
     * {@code _vance/hooks/} is a hook definition for the event its path
     * names.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(UrsaHookLoader.HOOK_PATH_ROOT);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        List<Finding> findings = new ArrayList<>();

        UrsaHookEventName event = eventFromPath(ctx.docPath());
        if (event == null) {
            findings.add(Finding.error(
                    target,
                    "hook-event-unknown",
                    "the event path segment is not a known event — the registry lists per event, "
                            + "so a hook under it never runs"));
            return findings;
        }
        try {
            parser.parse(content, event, UrsaHookSource.VANCE, "validation");
        } catch (RuntimeException e) {
            findings.add(Finding.error(target, "hook-parse", "the loader skips this hook: " + e.getMessage()));
        }
        return findings;
    }

    /**
     * The event the path carries: {@code _vance/hooks/<event>/<name>.yaml}
     * → the wire name between the second and third slash.
     */
    private static UrsaHookEventName eventFromPath(String docPath) {
        if (docPath == null) return null;
        String rest = docPath.substring(UrsaHookLoader.HOOK_PATH_ROOT.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String wire = rest.substring(0, slash);
        return UrsaHookEventName.isKnown(wire) ? UrsaHookEventName.ofWire(wire) : null;
    }
}
