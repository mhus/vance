package de.mhus.vance.brain.script;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScriptEngineConfig {

    @Bean(destroyMethod = "close")
    public Engine scriptEngine() {
        return Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * Shared {@link HostAccess} for every {@link org.graalvm.polyglot.Context}
     * built on top of {@link #scriptEngine()}. GraalVM requires that every
     * Context attached to the same Engine use an <em>identical</em>
     * HostAccess config; mixing per-service variants raises
     * {@code "Found different host access configuration for a context
     * with a shared engine."} at Context construction.
     *
     * <p>The policy: allow access to anything annotated
     * {@link HostAccess.Export} (the {@code vance} host API uses this),
     * plus map/list/array iteration so JS sees Java collections
     * naturally. Parse-only contexts ({@link JsValidationService}) don't
     * actually use this access, but they still must declare the same
     * config so they're compatible with the engine.
     */
    @Bean
    public HostAccess scriptHostAccess() {
        return HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowImplementationsAnnotatedBy(HostAccess.Implementable.class)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowArrayAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .build();
    }
}
