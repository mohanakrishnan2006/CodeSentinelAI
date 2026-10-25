package com.codesentinel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CodeSentinelApplicationTests {

    @Test
    @DisplayName("Application context loads successfully with test configuration")
    void contextLoads() {
        // Verifies that the Spring application context initializes without configuration errors
    }
}
