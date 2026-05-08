package de.mhus.vance.anus.access;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequiresAuth} by calling
 * {@link AccessService#requireAuthorized()} before the annotated method
 * runs. Both method-level and class-level usage are matched — class-level
 * applies to every method in the class.
 *
 * <p>Throws {@link NotAuthorizedException} on failure; Spring Shell catches
 * it via {@code AnusExceptionResolver} and prints a single red line instead
 * of a stack trace.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuthAspect {

    private final AccessService accessService;

    @Before("@annotation(de.mhus.vance.anus.access.RequiresAuth) "
            + "|| @within(de.mhus.vance.anus.access.RequiresAuth)")
    public void requireAuth() {
        accessService.requireAuthorized();
    }
}
