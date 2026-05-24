package de.mhus.vance.shared.marvin;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

/**
 * Registers Spring-Data-Mongo custom converters for Marvin's
 * {@link de.mhus.vance.api.marvin.TaskKind} so legacy v1 documents
 * (with {@code PLAN} or {@code AGGREGATE} discriminators) still
 * deserialize cleanly. See {@link TaskKindReadConverter}.
 */
@Configuration
public class MarvinMongoConfig {

    @Bean
    public MongoCustomConversions marvinMongoConversions() {
        return new MongoCustomConversions(List.of(
                new TaskKindReadConverter()));
    }
}
