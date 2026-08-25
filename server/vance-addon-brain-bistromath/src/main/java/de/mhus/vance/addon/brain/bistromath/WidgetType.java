package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The widget vocabulary of a view document.
 *
 * <p>Closed set, enforced when the document is read. The client renders each
 * one over a {@code V*} primitive — the style rule that DaisyUI classes stay
 * inside {@code @vance/components} applies to this addon like to any other, so
 * a new widget is a new entry here plus a branch in the renderer, never a
 * hand-rolled bit of markup in an app document.
 *
 * <p>{@link #PLANNED} names the widgets the schema is designed for but that
 * this iteration does not render. They are listed so an author who writes one
 * is told it arrives later, instead of being told it does not exist — the two
 * are very different pieces of news.
 */
@GenerateTypeScript("bistromath")
public enum WidgetType {

    /** Root of a view. Holds a heading and children. */
    PAGE,

    /** Horizontal strip of buttons. */
    TOOLBAR,

    /**
     * Children side by side, sharing the width evenly.
     *
     * <p>The one layout widget, and the only thing {@code @vance/components}
     * does <b>not</b> supply — layout there is Tailwind classes, not a
     * component. Named rather than configured: a {@code gap} or a
     * {@code flex-direction} in an app document is the line §1.3 draws, and a
     * row that splits evenly covers the case that actually comes up (two or
     * three inputs that belong together on one line).
     *
     * <p>{@link #TOOLBAR} stays separate. It wraps, is meant for controls, and
     * sizes to content; a row divides the width. Same axis, different job — and
     * a single widget with a flag would need a default that is wrong for one of
     * them.
     */
    ROW,

    /** Clickable label bound to an action. */
    BUTTON,

    /** Static paragraph. */
    TEXT,

    /** Markdown body, rendered read-only. */
    MARKDOWN,

    /** Rows of a folder-as-table, in declared columns. */
    TABLE,

    /**
     * Form-engine field list bound to a state key, and <b>editable</b>.
     *
     * <p>What the reader types goes back into the same key, so the program
     * picks it up with {@code vance.state.get(<key>)} and decides what to do
     * with it. The widget itself never stores anything — a form is a variable,
     * a button is the subroutine.
     */
    FORM,

    /**
     * A single text input bound to one state key.
     *
     * <p>Together with {@link #NUMBER}, {@link #TOGGLE} and {@link #SELECT} this
     * is the <b>direct</b> way in — no {@code FormFieldDto}, no field list, and
     * <b>no string encoding</b>. A {@code number} hands the program a number and
     * a {@code toggle} a boolean, because these write into <em>state</em>, not
     * into a document: there is no round trip to preserve, so there is nothing
     * to encode.
     *
     * <p>Deliberately <b>beside</b> {@link #FORM} rather than an extension of
     * it. {@code FormFieldDto} is a server-side model with four other consumers
     * — wizards, setting forms, document templates, kit tool templates — and
     * none of them wants a widget type added for this runtime. A model serving
     * five masters gets worse for each.
     */
    INPUT,

    /** A number input. Writes a number into state, or {@code null} when empty. */
    NUMBER,

    /** A checkbox. Writes a boolean. */
    TOGGLE,

    /** A choice from {@code options}. Writes the chosen value. */
    SELECT,

    /**
     * The same field list, <b>read-only</b>.
     *
     * <p>Separate widget rather than a {@code readOnly:} flag on {@code form}:
     * a boolean would need a default, and both defaults are wrong. Editable by
     * default surprises the reader of a detail view who types into a field that
     * nothing saves; read-only by default makes "why can't I type" the first
     * question every author asks. Two names, one meaning each.
     */
    DETAILS,

    /** Children as tab panes, captioned by their own label. */
    TABS,

    /**
     * Another document, rendered by whatever knows its kind.
     *
     * <p>The widget carries a path and nothing else: the host injects the
     * Cortex embed component, which routes on the document's kind. That is why
     * there is no {@code chart} and no {@code image} widget — a chart document
     * and an image document already have renderers, and duplicating them here
     * would mean this addon shipping a charting library.
     */
    EMBED,

    /**
     * Children once per element of a bound array.
     *
     * <p>Inside it, {@code from:} looks in the element first and falls back to
     * the surrounding state. Two levels, no path syntax — a widget still names
     * a key, it is just asked of the element.
     */
    REPEAT,

    /**
     * Children shown over the page, opened and closed by its {@code show:} key.
     *
     * <p>No handler form of its own and no {@code vance.ui.closeDialog()}: a
     * dialog is a widget whose condition happens to be interesting, so the
     * program opens it with {@code vance.state.set(key, true)} and closes it
     * with {@code false}. One rule instead of three, and the dialog is declared
     * where it is used.
     */
    DIALOG;

    /** Widgets the schema reserves but this iteration does not render. */
    static final Set<String> PLANNED = Set.of("chart");

    /** Widgets that carry children. */
    boolean allowsChildren() {
        return this == PAGE || this == TOOLBAR || this == ROW || this == TABS
                || this == REPEAT || this == DIALOG;
    }

    /** The lowercase spelling used in a view document. */
    String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    static @Nullable WidgetType parse(String raw) {
        for (WidgetType t : values()) {
            if (t.wire().equals(raw)) return t;
        }
        return null;
    }
}
