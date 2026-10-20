package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The one HTTP verb the app needs, behind an interface so the Drive client can
 * be tested without a network. {@code HttpURLConnection} rather than
 * {@code java.net.http}: this class is compiled into the Android app too.
 */
public interface HttpTransport {

    /** A response body for a GET; the length is -1 when the server did not say. */
    final class Response implements AutoCloseable {
        public final InputStream body;
        public final long contentLength;

        public Response(InputStream body, long contentLength) {
            this.body = body;
            this.contentLength = contentLength;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    Response get(String url) throws IOException;

    HttpTransport DEFAULT = url -> {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        int status = c.getResponseCode();
        if (status / 100 != 2) {
            String detail = "";
            // No InputStream.readAllBytes: Android only has it from API 33.
            try (InputStream err = c.getErrorStream()) {
                if (err != null) {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = err.read(chunk)) > 0 && buf.size() < 4096) {
                        buf.write(chunk, 0, n);
                    }
                    detail = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            c.disconnect();
            throw new IOException("HTTP " + status + " per " + url
                    + (detail.isEmpty() ? "" : ": " + abbreviate(detail)));
        }
        return new Response(c.getInputStream(), c.getContentLengthLong());
    };

    static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
