package com.hyunchang.webapp.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** Reject fractional or overflowing input instead of silently truncating it. */
public class ExactLongDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.currentToken().isNumeric())
            throw JsonMappingException.from(parser, "수량은 정수여야 합니다.");
        try {
            return parser.getDecimalValue().longValueExact();
        } catch (ArithmeticException error) {
            throw JsonMappingException.from(parser, "수량은 지원 범위 내의 정수여야 합니다.");
        }
    }
}
