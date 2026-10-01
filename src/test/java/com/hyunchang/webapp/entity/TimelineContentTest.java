package com.hyunchang.webapp.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

class TimelineContentTest {
    @Test
    void bothTimelinesAcceptLongTextAndRejectUtf8Overflow() {
        for (TimelineContent content : new TimelineContent[] {new History(), new Dating()}) {
            content.setDescription("가".repeat(21845));
            assertDoesNotThrow(content::validateContent);
            content.setDescription("가".repeat(21846));
            assertThrows(IllegalArgumentException.class, content::validateContent);
            content.setDescription("a".repeat(65535));
            assertDoesNotThrow(content::validateContent);
            content.setDescription("😀".repeat(16384));
            assertThrows(IllegalArgumentException.class, content::validateContent);
        }
    }

    @Test
    void inheritedFieldsKeepTextMappingAndJsonCompatibility() throws Exception {
        assertEquals(
                "TEXT",
                TimelineContent.class
                        .getDeclaredField("description")
                        .getAnnotation(Column.class)
                        .columnDefinition());
        ObjectMapper mapper = new ObjectMapper();
        for (Class<? extends TimelineContent> type : new Class[] {History.class, Dating.class}) {
            TimelineContent content =
                    mapper.readValue(
                            "{\"description\":\"긴 설명\",\"images\":\"[\\\"/uploads/test.jpg\\\"]\"}",
                            type);
            assertEquals("긴 설명", content.getDescription());
            assertEquals("[\"/uploads/test.jpg\"]", content.getImages());
            assertEquals(
                    "긴 설명",
                    mapper.readTree(mapper.writeValueAsString(content))
                            .get("description")
                            .asText());
        }
    }
}
