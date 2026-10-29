package com.codesentinel;

import com.codesentinel.service.ScanWorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ScanWorkerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "codesentinel.scan-worker.enabled=true")
@ActiveProfiles("test")
class ScanWorkerApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ScanWorkerService scanWorkerService;

    @Test
    void startsWorkerWithoutLoadingAuthenticationApi() {
        assertThat(scanWorkerService).isNotNull();
        assertThat(applicationContext.containsBean("authService")).isFalse();
        assertThat(applicationContext.containsBean("scanController")).isFalse();
    }
}
