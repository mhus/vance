package de.mhus.vance.foot.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Hands control to Picocli once the Spring context is fully built. Picocli's
 * {@code IFactory} is the Spring-aware factory contributed by
 * {@code picocli-spring-boot-starter}, so all {@code @Command} subcommands
 * resolve as Spring beans with constructor injection.
 */
@Component
public class FootRunner implements CommandLineRunner, ExitCodeGenerator {

    private final VanceFootCommand root;
    private final IFactory factory;
    private int exitCode;

    public FootRunner(VanceFootCommand root, IFactory factory) {
        this.root = root;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        // VanceFootCommand is the single Picocli root — flags steer the
        // mode (REPL vs. headless, profile, tools on/off). No subcommand
        // dispatch here.
        exitCode = new CommandLine(root, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
