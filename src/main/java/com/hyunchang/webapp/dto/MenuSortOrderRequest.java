package com.hyunchang.webapp.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MenuSortOrderRequest {
    private Long id;
    private int sortOrder;
}
