package dev.olivelli.fxcenter.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serves canned bodies by URL substring and records what was asked for. */
final class FakeHttp implements HttpTransport {

    private final Map<String, byte[]> routes = new LinkedHashMap<>();
    final List<String> requests = new ArrayList<>();

    FakeHttp on(String urlPart, byte[] body) {
        routes.put(urlPart, body);
        return this;
    }

    FakeHttp on(String urlPart, String body) {
        return on(urlPart, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Response get(String url) throws IOException {
        requests.add(url);
        for (Map.Entry<String, byte[]> e : routes.entrySet()) {
            if (url.contains(e.getKey())) {
                return new Response(new ByteArrayInputStream(e.getValue()), e.getValue().length);
            }
        }
        throw new IOException("HTTP 404 per " + url);
    }
}
