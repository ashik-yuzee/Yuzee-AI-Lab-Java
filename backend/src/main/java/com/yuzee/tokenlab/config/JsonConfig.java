package com.yuzee.tokenlab.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON.stringify parity for response bodies: a null POJO property is left out (undefined in the
 * original), but a null value inside a Map is written as null, as server.ts does for literals
 * like the benchmark row's {thinkingTokens: null} or /api/routing/llm's {result: null}.
 */
@Configuration
public class JsonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer mapNullValues() {
        return builder -> builder.postConfigurer(mapper -> mapper.setDefaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)));
    }
}
