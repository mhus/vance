/**
 * Bundled role-based permission provider (Simple-Auth).
 *
 * <p>Implements the {@link de.mhus.vance.shared.permission.PermissionResolver}
 * SPI with a minimal RBAC model: roles {@code READER < WRITER < ADMIN} on two
 * scopes (TENANT, PROJECT), stored as {@code permission_grants}. Everything
 * deeper (Session / ThinkProcess / Document / InboxItem) inherits from the
 * project or is handled by the code rules R1–R7. See
 * {@code specification/public/permission-system.md} and
 * {@code planning/permission-system-concept.md}.
 *
 * <p>Depends only on {@code vance-shared} (+ toolpack / spring-web / spring-shell
 * as provided) so the resolver + grant storage + bootstrap load into both the
 * Brain and the anus context. The web/LLM surfaces are
 * {@code @ConditionalOnWebApplication}-gated; the anus CRUD commands are
 * {@code @ConditionalOnClass(spring-shell)}-gated.
 */
@NullMarked
package de.mhus.vance.simpleauth;

import org.jspecify.annotations.NullMarked;
