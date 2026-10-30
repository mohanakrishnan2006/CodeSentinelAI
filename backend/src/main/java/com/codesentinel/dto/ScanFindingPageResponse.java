package com.codesentinel.dto;

import org.springframework.data.domain.Page;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ScanFindingPageResponse {

    List<ScanFindingResponse> content;
    int page;
    int size;
    long totalElements;
    int totalPages;

    public static ScanFindingPageResponse fromPage(Page<ScanFindingResponse> page) {
        return ScanFindingPageResponse.builder()
                .content(List.copyOf(page.getContent()))
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}
