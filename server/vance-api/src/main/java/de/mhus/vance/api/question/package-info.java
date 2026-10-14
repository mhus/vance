/**
 * Wire-contract types for asking the user several questions at once and
 * getting a separate, structured answer per question back.
 *
 * <p>One {@link de.mhus.vance.api.question.QuestionSetDto} travels to the
 * client (chat message meta {@code questionSet}); the answers come back as a
 * {@link de.mhus.vance.api.question.QuestionSetAnswerDto} riding on the
 * {@code process-steer} request. Not engine-specific — the first consumer is
 * the Wonko engine ({@code planning/wonko-engine.md} §8).
 */
@NullMarked
package de.mhus.vance.api.question;

import org.jspecify.annotations.NullMarked;
