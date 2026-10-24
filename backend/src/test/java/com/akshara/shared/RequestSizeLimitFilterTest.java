package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Request bodies over the cap are a 413, whether their size is declared or only found while reading. */
class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

    @Test
    void aDeclaredJsonBodyOverTheLimitIsRefusedBeforeAnythingReadsIt() throws Exception {
        MockHttpServletRequest request = declaring(RequestSizeLimitFilter.MAX_BODY_BYTES + 1, "/api/students");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"status\":413");
        assertThat(chain.getRequest()).as("the controller is never reached").isNull();
    }

    @Test
    void uploadsMayBeLargerThanJsonBodies() throws Exception {
        MockHttpServletRequest request = declaring(10L * 1024 * 1024, "/api/school-import");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=x");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("10 MB is under the 26 MB upload limit").isNotNull();
        assertThat(RequestSizeLimitFilter.limitFor(request)).isEqualTo(RequestSizeLimitFilter.MAX_UPLOAD_BYTES);
    }

    @Test
    void aBodyWithinTheLimitPassesUnchanged() throws Exception {
        MockHttpServletRequest request = json("{\"name\":\"Asha\"}".getBytes());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aChunkedBodyIsCountedWhileReadAndStopsAtTheLimit() throws Exception {
        byte[] body = new byte[(int) RequestSizeLimitFilter.MAX_BODY_BYTES + 10];
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/govreport/students") {
            @Override
            public long getContentLengthLong() {
                return -1; // Transfer-Encoding: chunked
            }

            @Override
            public ServletInputStream getInputStream() {
                return stream(body);
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest wrapped = chain.getRequest();
        assertThat(wrapped).isNotSameAs(request);
        assertThatThrownBy(() -> wrapped.getInputStream().readAllBytes())
                .isInstanceOf(RequestSizeLimitFilter.BodyTooLargeException.class);
    }

    @Test
    void theHandlerAnswers413WhenReadingStoppedAtTheLimit() throws Exception {
        IOException tooLarge = readPastTheLimit();
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException("I/O error", tooLarge,
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

        assertThat(new ApiExceptionHandler().handleUnreadable(unreadable).getStatusCode())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(new ApiExceptionHandler().handleUnexpected(new RuntimeException(tooLarge)).getStatusCode())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    private static IOException readPastTheLimit() throws Exception {
        byte[] body = new byte[(int) RequestSizeLimitFilter.MAX_BODY_BYTES + 1];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return stream(body);
            }
        };
        MockFilterChain chain = new MockFilterChain();
        new RequestSizeLimitFilter().doFilter(request, new MockHttpServletResponse(), chain);
        try {
            chain.getRequest().getInputStream().readAllBytes();
        } catch (IOException e) {
            return e;
        }
        throw new AssertionError("expected the read to fail");
    }

    /** A request whose Content-Length header says {@code length}; nothing reads its (empty) body. */
    private static MockHttpServletRequest declaring(long length, String path) {
        return new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return length;
            }
        };
    }

    private static MockHttpServletRequest json(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/students");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }

    private static ServletInputStream stream(byte[] body) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }
        };
    }
}
