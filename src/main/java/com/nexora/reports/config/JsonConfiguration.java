package com.nexora.reports.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict request bodies: besides rejecting unknown properties ({@code spring.jackson.deserialization}), numbers must
 * be JSON numbers, booleans JSON booleans, and duplicate keys are refused, so a request means exactly one thing.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictRequests() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfig(LogicalType.Integer, c -> c.setCoercion(CoercionInputShape.String,
                        CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Boolean, c -> c.setCoercion(CoercionInputShape.String,
                        CoercionAction.Fail).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail));
    }
}
