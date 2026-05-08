package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.NotAuthorizedException;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.command.CommandExceptionResolver;
import org.springframework.shell.command.CommandHandlingResult;
import org.springframework.stereotype.Component;

/**
 * Maps {@link NotAuthorizedException} (and other domain "expected" failures)
 * to a single red line so the REPL doesn't dump a Java stack trace at the
 * operator. Returning {@code null} delegates back to Spring Shell's default
 * handling.
 */
@Component
public class AnusExceptionResolver implements CommandExceptionResolver {

    @Override
    public @Nullable CommandHandlingResult resolve(Exception ex) {
        if (ex instanceof NotAuthorizedException) {
            return CommandHandlingResult.of("\033[31m" + ex.getMessage() + "\033[0m\n", 2);
        }
        return null;
    }
}
