/**
 * JavaScript evaluation tool. {@link
 * de.mhus.vance.brain.tools.js.JsEngine} picks the best available
 * runtime (GraalJS → Rhino → none) at startup; {@link
 * de.mhus.vance.brain.tools.js.JavaScriptTool} exposes it to the LLM.
 */
@NullMarked
package de.mhus.vance.brain.tools.js;

import org.jspecify.annotations.NullMarked;
