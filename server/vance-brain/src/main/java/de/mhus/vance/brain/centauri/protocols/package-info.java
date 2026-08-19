/**
 * Wire-format adapters for Centauri feed sources — one {@code FeedProtocol}
 * bean per family of sources.
 *
 * <p>{@code ode} is the contract {@code vance-ode-centauri} defines for any
 * foreign application that wants to be a source. The others are genuinely
 * foreign APIs that never heard of Vancetope, and they are the reason the SPI
 * is a contract rather than one consumer's API with extra steps.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.brain.centauri.protocols;

import org.jspecify.annotations.NullMarked;
