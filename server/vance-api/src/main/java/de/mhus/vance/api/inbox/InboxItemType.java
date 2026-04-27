package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Discriminator for an {@code InboxItem}. Each type has its own
 * payload-shape (in the question) and answer-shape (in the response,
 * for {@code requiresAction} types).
 *
 * <p>Asks (require user action) vs. Outputs (informational only):
 *
 * <ul>
 *   <li>Asks: {@link #APPROVAL}, {@link #DECISION}, {@link #FEEDBACK},
 *       {@link #ORDERING}, {@link #STRUCTURE_EDIT}.</li>
 *   <li>Outputs: {@link #OUTPUT_TEXT}, {@link #OUTPUT_IMAGE},
 *       {@link #OUTPUT_DOCUMENT}.</li>
 * </ul>
 *
 * <p>v1 implements {@link #APPROVAL}, {@link #DECISION},
 * {@link #FEEDBACK}, {@link #OUTPUT_TEXT}; the rest are spec-only.
 */
@GenerateTypeScript("inbox")
public enum InboxItemType {
    APPROVAL,
    DECISION,
    FEEDBACK,
    ORDERING,
    STRUCTURE_EDIT,
    OUTPUT_TEXT,
    OUTPUT_IMAGE,
    OUTPUT_DOCUMENT
}
