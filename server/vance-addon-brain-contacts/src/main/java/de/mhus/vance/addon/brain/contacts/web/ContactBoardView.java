package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A contact board for one reader. Every mutation answers with the whole
 * board — the order after a change is the server's answer. {@code warning}
 * carries a visibility note after a pin.
 */
@GenerateTypeScript("contacts")
public record ContactBoardView(
        String path,
        @Nullable String title,
        @Nullable String description,
        List<ContactPinView> pins,
        @Nullable String warning) {

    public static ContactBoardView of(ContactBoardService.ResolvedBoard b, @Nullable String warning) {
        return new ContactBoardView(
                b.path(),
                b.board().title(),
                b.board().description(),
                b.pins().stream().map(ContactPinView::of).toList(),
                warning);
    }
}
