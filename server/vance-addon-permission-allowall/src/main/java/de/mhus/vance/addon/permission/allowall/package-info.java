/**
 * Allow-all permission provider addon.
 *
 * <p>Contributes a single {@link de.mhus.vance.shared.permission.PermissionResolver}
 * that permits every check. It exists so a deployment can satisfy the
 * mandatory "exactly one provider" guard (see
 * {@link de.mhus.vance.shared.permission.PermissionService}) without any real
 * authorization — the right choice for local development, tests, and
 * intentionally open installations.
 *
 * <p>Registered classpath-based via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}:
 * drop the JAR on the classpath and the provider appears; remove it and it is
 * gone. Production deployments swap this addon for {@code vance-addon-simpleauth}
 * (or an enterprise governor) — never both at once, or the guard fails on
 * startup with two providers.
 */
@NullMarked
package de.mhus.vance.addon.permission.allowall;

import org.jspecify.annotations.NullMarked;
