/**
 * Annotations that drive the TypeScript code generator.
 *
 * Consumed by the Maven plugin {@code generate-java-to-ts-maven-plugin} at build time.
 * The plugin matches annotations by simple name, so the classes under this package
 * are the canonical user-facing markers (@GenerateTypeScript, @TypeScript, @TypeScriptImport).
 *
 * Retention is SOURCE — the annotations are stripped at compile time and have no
 * runtime cost. Apply them to DTOs / enums in vance-api that should be exported
 * to the generated {@code @vance/api} TypeScript package.
 */
@NullMarked
package de.mhus.vance.api.annotations;

import org.jspecify.annotations.NullMarked;
