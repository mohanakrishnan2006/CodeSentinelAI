package com.codesentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeSentinelApplication {

    public static void main(String[] args) {
        boolean workerProcess = java.util.Arrays.stream(args)
                .anyMatch(argument -> argument.equals("--codesentinel.worker-process=true"));
        if (workerProcess) {
            ScanWorkerApplication.main(args);
            return;
        }
        SpringApplication.run(CodeSentinelApplication.class, args);
    }
}
