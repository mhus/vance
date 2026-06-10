/**
 * Image-generation stack for the Brain. Parallel to the chat stack in
 * {@link de.mhus.vance.brain.ai}: a typed {@link AiImageModelProvider}
 * interface, a dispatching {@link AiImageService}, and per-vendor
 * sub-packages for the concrete provider implementations.
 *
 * <p>Used by the Fenchurch tool family (image_generate, image_style_set,
 * image_style_get, image_style_prompt) for the only image-generation
 * code path Vance offers.
 *
 * <p>Provider beans live in sub-packages (e.g.
 * {@code de.mhus.vance.brain.ai.image.openai}) as Spring beans
 * implementing {@link AiImageModelProvider}. {@link AiImageService}
 * auto-discovers them.
 */
@NullMarked
package de.mhus.vance.brain.ai.image;

import org.jspecify.annotations.NullMarked;
