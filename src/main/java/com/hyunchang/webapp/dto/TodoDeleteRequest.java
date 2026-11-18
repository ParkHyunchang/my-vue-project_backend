package com.hyunchang.webapp.dto;

import java.util.List;

public record TodoDeleteRequest(List<Long> ids) {}
