package com.akshara.shared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies before anything reads them: {@value #MAX_UPLOAD_BYTES} bytes for multipart uploads (the same
 * limit as spring.servlet.multipart.max-request-size) and {@value #MAX_BODY_BYTES} for everything else: JSON forms
 * and the CSV student import of up to 2,000,000 characters, which is up to 6 MB of UTF-8 in Hindi or Telugu.
 * A declared Content-Length over the limit is refused at once with 413; a body sent without one (chunked) is counted
 * as it is read and fails with {@link BodyTooLargeException}, which {@link ApiExceptionHandler} answers with the same
 * 413. In AWS the firewall only counts large bodies, so this is the limit that holds.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 8L * 1024 * 1024;
    public static final long MAX_UPLOAD_BYTES = 26L * 1024 * 1024;

    static final String TOO_LARGE = "{\"type\":\"about:blank\",\"title\":\"Too large\",\"status\":413,"
            + "\"detail\":\"The request is too large.\"}";

    /** A body that went over the limit while it was being read. */
    public static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long limit) {
            super("Request body over " + limit + " bytes");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        if (request.getContentLengthLong() > limit) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(TOO_LARGE);
            return;
        }
        chain.doFilter(request.getContentLengthLong() >= 0 ? request : new Limited(request, limit), response);
    }

    static long limitFor(HttpServletRequest request) {
        String type = request.getContentType();
        boolean multipart = type != null && type.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
        return multipart ? MAX_UPLOAD_BYTES : MAX_BODY_BYTES;
    }

    /** A request whose body may be read only up to {@code limit} bytes. */
    private static final class Limited extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class Counting extends ServletInputStream {

        private final ServletInputStream in;
        private final long limit;
        private long read;

        Counting(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws BodyTooLargeException {
            read += n;
            if (read > limit) {
                throw new BodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
