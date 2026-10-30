package de.mhus.vance.api.recipe;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Which surface level a recipe belongs to — the picker filter behind
 * {@code surface:} in the recipe YAML.
 *
 * <p>Matches the reader's UI surface preference ({@code webui.uiLevel}):
 * a reader on the standard surface does not get {@link #EXPERT} recipes
 * offered. Display filter only; naming a recipe explicitly still works.
 */
@GenerateTypeScript("recipe")
public enum RecipeSurface {

    /** Offered to everyone — the default. */
    STANDARD,

    /** Offered only to readers who chose the expert surface. */
    EXPERT
}
