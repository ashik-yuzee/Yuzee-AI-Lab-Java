package com.yuzee.tokenlab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "gemini.api-key=test-key",
    "spring.datasource.url="
})
class TokenlabApplicationTests {

    @Test
    void contextLoads() {
    }
}
