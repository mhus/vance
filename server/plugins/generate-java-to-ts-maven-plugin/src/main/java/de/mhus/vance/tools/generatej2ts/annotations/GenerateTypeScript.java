package de.mhus.vance.tools.generatej2ts.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert eine Java-Klasse oder ein Enum zur Generierung eines TypeScript-Typs.
 *
 * Verwendung:
 * - @GenerateTypeScript("subfolder")
 * - @GenerateTypeScript(value = "subfolder")
 *
 * Der optionale Wert definiert einen Unterordner relativ zum Ausgabeordner,
 * in dem die erzeugte .ts-Datei abgelegt wird.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface GenerateTypeScript {
    /**
     * Optionaler Unterordner für die Ausgabe (z. B. "models").
     */
    String value() default "";

    /**
     * Optionaler Name des zu erzeugenden TypeScript-Interfaces bzw. Enums.
     *
     * Wenn gesetzt, wird dieser Name anstelle des Java-Klassennamens verwendet.
     * Beispiel:
     *   @GenerateTypeScript(value="models", name="Human")
     * erzeugt eine Datei "Human.ts" (sofern kein expliziter Dateiname via value mit 
     * ".ts" angegeben ist) mit "export interface Human { ... }".
     */
    String name() default "";
}
