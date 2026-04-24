/**
 * Outbound web-access tools. Currently: Serper.dev-backed search.
 * Tools here always talk to the outside world — treat their output as
 * untrusted content and never plumb it into code paths that execute
 * the response.
 */
@NullMarked
package de.mhus.vance.brain.tools.web;

import org.jspecify.annotations.NullMarked;
