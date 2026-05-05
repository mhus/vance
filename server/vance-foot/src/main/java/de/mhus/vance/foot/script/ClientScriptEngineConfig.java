package de.mhus.vance.foot.script;

import org.graalvm.polyglot.Engine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClientScriptEngineConfig {

    @Bean(destroyMethod = "close")
    public Engine clientScriptEngine() {
        return Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }
}
