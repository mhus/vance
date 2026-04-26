/**
 * Client-side file tools — read, write, edit, list on the foot host's
 * actual filesystem. Absolute or working-directory-relative paths are
 * accepted; there is no sandbox (the whole point of {@code client_*}
 * tools is direct host access). Future security policy goes through
 * {@link de.mhus.vance.foot.tools.ClientSecurityService}.
 */
@NullMarked
package de.mhus.vance.foot.tools.file;

import org.jspecify.annotations.NullMarked;
