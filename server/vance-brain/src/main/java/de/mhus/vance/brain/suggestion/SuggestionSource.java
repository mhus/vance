package de.mhus.vance.brain.suggestion;

import de.mhus.vance.api.form.FormSuggestionDto;
import java.util.List;

/**
 * Supplies live suggestions for form fields that name it in
 * {@code suggestionsFrom}. Implemented by addons (the contacts addon offers
 * {@code mail-recipients}); a field whose source is not installed simply gets
 * none.
 *
 * <p>A source answers with what the subject may see and nothing more — the
 * suggestion list is a read path like any other.
 */
public interface SuggestionSource {

    /** The name forms reference in {@code suggestionsFrom}. Lower-case, kebab. */
    String name();

    List<FormSuggestionDto> suggest(SuggestionRequest request);
}
