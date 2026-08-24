package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for {@code app-view} — a Bistromath view document.
 *
 * <p>Registering the kind buys three separate things, and the third is the one
 * that was missing:
 *
 * <ul>
 *   <li>{@code doc_write} accepts {@code kind: app-view} as a known kind rather
 *       than an unrecognised string.</li>
 *   <li>The Cortex resolves a renderer for it, so opening a view document shows
 *       a preview instead of raw YAML.</li>
 *   <li>{@code kind_validate} reaches it. Until now the <b>only</b> thing that
 *       checked a view was {@code app_rebuild}, which needs a whole app around
 *       the document; a view written on its own was unchecked until somebody
 *       opened the app.</li>
 * </ul>
 *
 * <p>Validation is {@link ViewParser} itself, not a second set of rules. The
 * parser already refuses everything a view can get wrong and its messages name
 * the path inside the document — writing a validator beside it would mean two
 * definitions of a valid view, drifting apart at the first change.
 */
@Service
public class AppViewKindHandler implements KindHandler {

    @Override
    public String getName() {
        return BistromathConfig.VIEW_KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath())
                ? BistromathConfig.VIEW_KIND : ctx.docPath();
        try {
            ViewParser.parse(content, target);
            return List.of();
        } catch (ToolException e) {
            // The parser's message already carries the document and the path
            // inside it (`.children[3].on.click`), which is the whole value —
            // re-wording it here would only make it shorter and vaguer.
            return List.of(Finding.error(target, "app-view", e.getMessage()));
        }
    }

    /**
     * Claim a document only on the shape a view root actually has.
     *
     * <p>Deliberately narrow, because a loose detector wins over more specific
     * kinds that sort after it and types the write wrong with no error
     * anywhere. A root mapping whose {@code type} is {@code page} is the
     * canonical on-disk form of a view and of nothing else in the tree; a bare
     * {@code type:} of anything else is not enough, since plenty of YAML
     * carries a {@code type} key.
     */
    @Override
    public boolean detects(String content) {
        if (content == null || content.isBlank()) return false;
        Object root;
        try {
            root = BistromathYaml.load(content, BistromathConfig.VIEW_KIND);
        } catch (ToolException e) {
            return false;
        }
        return root instanceof java.util.Map<?, ?> map && "page".equals(map.get("type"));
    }
}
