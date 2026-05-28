/**
 * Per-user memory: persona summary + factual journal, shared across
 * engines that want to address the user directly (Eddie, Arthur).
 * Storage lives in the user's hub project ({@code _user_<login>}); the
 * service is engine-agnostic and takes the user-project name as a
 * parameter so non-hub-resident engines (Arthur) can target it.
 */
@NullMarked
package de.mhus.vance.brain.usermemory;

import org.jspecify.annotations.NullMarked;
