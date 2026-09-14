package de.mhus.vance.addon.brain.workbook.action;

/**
 * SPI for one {@code vance-button} action type. One implementation per
 * fence {@code type:} value, registered as a Spring {@code @Component};
 * {@link WorkbookButtonService} injects them all and dispatches by
 * {@link #type()}. <b>Adding a new action = add one @Component here — no
 * central switch to touch.</b>
 *
 * <p>Handlers run with the caller's permissions (the controller enforces
 * {@code WRITE} on the project before dispatch) and read the page fresh
 * from the {@code DocumentService} — never from client-supplied content.
 */
public interface ButtonActionHandler {

    /** The fence {@code type:} value this handler implements, e.g. {@code "script"}. */
    String type();

    /** Execute the action; return a summary message or {@code null}. */
    ButtonActionResult run(ButtonActionContext ctx);
}
