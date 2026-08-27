/**
 * The part of Trillian that outlives the brain's classpath.
 *
 * <p>Trillian itself — the two engines, the natures, the lifecycle — lives in
 * {@code vance-brain}. Here are only the two persisted markers by which an
 * outside process can recognise a Trillian pair and find the service account it
 * minted, plus the project-maintenance handler that has to delete that account
 * again. The admin shell has no brain and would otherwise leave the accounts
 * behind.
 */
@NullMarked
package de.mhus.vance.shared.trillian;

import org.jspecify.annotations.NullMarked;
