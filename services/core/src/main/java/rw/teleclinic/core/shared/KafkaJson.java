package rw.teleclinic.core.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/** Events on Kafka use the same JSON as the API (ISO-8601 dates), not the serializer's own defaults. */
@Configuration
class KafkaJson {

    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaProducerFactoryCustomizer applicationJson(ObjectMapper objectMapper) {
        return factory -> ((DefaultKafkaProducerFactory<Object, Object>) factory)
                .setValueSerializer(new JsonSerializer<>(objectMapper).noTypeInfo());
    }
}
