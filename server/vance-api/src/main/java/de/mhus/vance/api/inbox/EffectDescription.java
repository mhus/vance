package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What an {@link InboxEffect} would do, rendered by the server for the
 * person who has to decide.
 *
 * <p>These are the <b>facts</b>: they come from the effect's own storage,
 * not from the item's free-text body. That split is the point — the body
 * carries a reason written by an agent and possibly echoing injected
 * text, while what actually happens on approval is described here and
 * nowhere else. A UI must render the two differently.
 *
 * <p>Deliberately presentation-neutral label/value pairs: the inbox UI
 * shows them without knowing which effect produced them, so a new effect
 * type needs no UI change.
 *
 * @param status       lifecycle of the underlying request, e.g.
 *                     {@code PENDING} / {@code APPROVED} / {@code FAILED}
 * @param statusDetail why it failed or lapsed, when there is more to say
 * @param facts        ordered label/value pairs describing the mutation
 */
@GenerateTypeScript("inbox")
public record EffectDescription(
        String status,
        @Nullable String statusDetail,
        List<EffectFact> facts) {
}
