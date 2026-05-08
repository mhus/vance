package de.mhus.vance.anus.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring-Shell command (method) — or every command in a class —
 * as requiring an active authorisation. The {@code AuthAspect} intercepts
 * matching calls and rejects them with {@link NotAuthorizedException} when
 * the {@link AccessService} reports no active session.
 *
 * <p>Each successful interception also extends the sliding-window timeout
 * by another {@code vance.anus.access.timeout} duration.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAuth {
}
