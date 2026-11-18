package com.hyunchang.webapp.dto;

import java.util.List;

public record TodoDeletePreview(List<Long> ids, int count) {}
