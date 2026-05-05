package de.mhus.vance.brain.script;

import org.graalvm.polyglot.Engine;
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
}
