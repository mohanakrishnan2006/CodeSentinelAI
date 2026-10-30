package com.codesentinel.service;

public interface ScanExecutor {

    ScanExecutionResult execute(ScanWorkItem workItem) throws Exception;
}
