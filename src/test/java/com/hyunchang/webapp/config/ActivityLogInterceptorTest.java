package com.hyunchang.webapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(OutputCaptureExtension.class)
class ActivityLogInterceptorTest {
    private final ActivityLogInterceptor interceptor = new ActivityLogInterceptor();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test-user",
                                null,
                                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void errorDispatchLogsOriginalMissingUploadAndPreservesStartTime(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/uploads/images/missing.jpg");
        long originalStart = System.currentTimeMillis() - 1000;
        request.setAttribute("activityLog.startTime", originalStart);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        interceptor.preHandle(request, response, this);
        interceptor.afterCompletion(request, response, this, null);

        assertThat(request.getAttribute("activityLog.startTime")).isEqualTo(originalStart);
        assertThat(output).contains("GET /uploads/images/missing.jpg → 404");
        assertThat(output).doesNotContain("GET /error");
    }

    @Test
    void successfulUploadsRemainQuiet(CapturedOutput output) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/uploads/images/ok.jpg");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, this);
        interceptor.afterCompletion(request, response, this, null);

        assertThat(output).doesNotContain("[ACTION]");
    }

    @Test
    void ordinaryRequestsStillLogTheirOwnPath(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/histories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, this);
        interceptor.afterCompletion(request, response, this, null);

        assertThat(output).contains("GET /api/histories → 200");
    }
}
