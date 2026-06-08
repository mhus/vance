/**
 * Follow-up — context-based suggestion subsystem behind the
 * follow-up REST endpoint. See {@code specification/follow-up.md} for
 * the design, {@code specification/light-llm-service.md} for the
 * LLM-call helper this service builds on.
 *
 * <p>The service receives a text fragment plus a cursor position
 * (character offset from start) and asks the {@code follow-up} recipe
 * for up to N follow-up suggestions matching the surrounding context.
 * It is consumed both by the Web-UI (chat prompt + text editor) and
 * future client surfaces — anywhere a "what could come next" hint is
 * useful.
 */
@NullMarked
package de.mhus.vance.brain.followup;

import org.jspecify.annotations.NullMarked;
