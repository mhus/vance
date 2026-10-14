/**
 * Wire-contract types for asking the user several questions at once and
 * getting a separate, structured answer per question back.
 *
 * <p>One {@link de.mhus.vance.api.question.QuestionSetDto} travels to the
 * client as a {@code questions} fence (a document kind, spec
 * {@code doc-kind-questions.md}); the answers come back as a
 * {@link de.mhus.vance.api.question.QuestionSetAnswerDto} riding on the
 * {@code process-steer} request. Not engine-specific — the first consumer is
 * the Wonko engine ({@code planning/wonko-engine.md} §8).
 */
@NullMarked
package de.mhus.vance.api.question;

import org.jspecify.annotations.NullMarked;
