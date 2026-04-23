package de.mhus.vance.tools.generatej2ts.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation auf Klassen-/Enum-Ebene, um zusätzliche TypeScript-Imports in die
 * generierte .ts-Datei zu übernehmen.
 *
 * Beispiel:
 *   @TypeScriptImport("import { ColorHex } from '../types/ColorHex';")
 *   oder
 *   @TypeScriptImport({
 *       "import { A } from './A';",
 *       "import { B } from './B';"
 *   })
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TypeScriptImport {
    String[] value();
}
