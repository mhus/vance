/**
 * Document-change event routing — receives {@link
 * de.mhus.vance.shared.document.DocumentChangedEvent}s from
 * {@code DocumentService}, decides which pod(s) need to refresh their
 * caches, and re-publishes a brain-layer {@link
 * de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent} that
 * the actual consumers (ServerToolRegistry, UrsaScheduler, UrsaHook, …)
 * subscribe to.
 *
 * <p>Spec: {@code planning/document-change-events.md}.
 */
@NullMarked
package de.mhus.vance.brain.documents.events;

import org.jspecify.annotations.NullMarked;
