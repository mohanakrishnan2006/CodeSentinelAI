package com.codesentinel.service;

import com.codesentinel.model.ScannerType;

public interface ScannerAdapter {

    ScannerType scannerType();

    ScanExecutionResult execute(ScanWorkItem workItem);
}
