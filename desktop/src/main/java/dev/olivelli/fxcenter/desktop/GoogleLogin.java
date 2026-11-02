package dev.olivelli.fxcenter.desktop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Google sign-in for the one thing that needs it: writing {@code scaletta.txt}
 * to the show's folder. Playing never logs in; it reads with the API key.
 *
 * <p>The OAuth "installed app" flow: the browser opens Google's consent page,
 * Google redirects to a one-shot server on 127.0.0.1, and the code is traded
 * (with PKCE) for a refresh token kept in {@code ~/.fx-center/google-login.properties}.
 * A desktop client's secret is not confidential by Google's own rules, hence
 * in the source like the API key.
 */
final class GoogleLogin {

    // OAuth client "FX Center desktop" (type Desktop app) in Cloud project 989019018687.
    static final String CLIENT_ID = System.getProperty("fxcenter.oauth.clientId",
            "989019018687-ltm0tdsg52chdib7lg2k0d0po9ejmaqj.apps.googleusercontent.com");
    static final String CLIENT_SECRET = System.getProperty("fxcenter.oauth.clientSecret", "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");

    /** Full Drive: the app must be able to rewrite a scaletta.txt someone uploaded by hand. */
    static final String SCOPE = "https://www.googleapis.com/auth/drive";

    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";

    private final Path store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private String accessToken;
    private long accessExpires;

    GoogleLogin(Path homeDir) {
        this.store = homeDir.resolve("google-login.properties");
    }

    static boolean configured() {
        return !CLIENT_ID.isEmpty();
    }

    /** Whether a refresh token is saved: saving will not open the browser. */
    boolean loggedIn() {
        return refreshToken() != null;
    }

    /** Forgets the saved login. */
    void logout() throws IOException {
        Files.deleteIfExists(store);
        accessToken = null;
    }

    /**
     * A valid access token, opening the browser for consent when there is no
     * saved login. Blocking (up to a few minutes while the user decides): call
     * off the UI thread.
     */
    synchronized String accessToken() throws IOException {
        if (!configured()) {
            throw new IOException("Il login Google non è ancora configurato in questa versione dell'app");
        }
        if (accessToken != null && System.currentTimeMillis() < accessExpires - 60_000) {
            return accessToken;
        }
        String refresh = refreshToken();
        if (refresh != null) {
            try {
                JsonObject t = token(Map.of("grant_type", "refresh_token", "refresh_token", refresh));
                remember(t);
                return accessToken;
            } catch (IOException e) {
                if (!String.valueOf(e.getMessage()).contains("invalid_grant")) {
                    throw e;
                }
                logout(); // revoked or expired: ask again
            }
        }
        browserLogin();
        return accessToken;
    }

    private void browserLogin() throws IOException {
        String verifier = random(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String state = random(16);
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            boolean ok = q.containsKey("code");
            byte[] page = ("<!doctype html><meta charset=utf-8><title>FX Center</title>"
                    + "<body style='font-family:sans-serif;margin:3em'><h2>"
                    + (ok ? "Fatto. Puoi chiudere questa scheda e tornare a FX Center."
                    : "Accesso non concesso: " + q.getOrDefault("error", "?"))
                    + "</h2>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(page);
            }
            answer.complete(q);
        });
        server.start();
        try {
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
            String url = AUTH + "?client_id=" + enc(CLIENT_ID) + "&redirect_uri=" + enc(redirect)
                    + "&response_type=code&scope=" + enc(SCOPE) + "&access_type=offline&prompt=consent"
                    + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=" + state;
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (IOException | RuntimeException e) {
                throw new IOException("Non riesco ad aprire il browser per l'accesso a Google: " + e.getMessage(), e);
            }
            Map<String, String> q;
            try {
                q = answer.get(5, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                throw new IOException("Accesso a Google non completato entro 5 minuti");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Accesso a Google interrotto");
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            }
            if (!state.equals(q.get("state")) || !q.containsKey("code")) {
                throw new IOException("Accesso a Google non concesso (" + q.getOrDefault("error", "risposta inattesa") + ")");
            }
            Map<String, String> form = new HashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", q.get("code"));
            form.put("redirect_uri", redirect);
            form.put("code_verifier", verifier);
            JsonObject t = token(form);
            remember(t);
            if (t.has("refresh_token")) {
                saveRefreshToken(t.get("refresh_token").getAsString());
            }
        } finally {
            server.stop(0);
        }
    }

    private JsonObject token(Map<String, String> params) throws IOException {
        Map<String, String> form = new HashMap<>(params);
        form.put("client_id", CLIENT_ID);
        form.put("client_secret", CLIENT_SECRET);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.append(body.length() == 0 ? "" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> r = send(http, req);
        if (r.statusCode() != 200) {
            throw new IOException("Google ha rifiutato l'accesso: HTTP " + r.statusCode() + " " + abbreviate(r.body()));
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    static HttpResponse<String> send(HttpClient http, HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrotto");
        }
    }

    private void remember(JsonObject t) {
        accessToken = t.get("access_token").getAsString();
        accessExpires = System.currentTimeMillis() + (t.has("expires_in") ? t.get("expires_in").getAsLong() : 3000) * 1000;
    }

    private String refreshToken() {
        if (!Files.isRegularFile(store)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(store)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String t = p.getProperty("refreshToken", "");
        return t.isEmpty() || !CLIENT_ID.equals(p.getProperty("clientId")) ? null : t;
    }

    private void saveRefreshToken(String token) throws IOException {
        Properties p = new Properties();
        p.setProperty("clientId", CLIENT_ID);
        p.setProperty("refreshToken", token);
        Files.createDirectories(store.getParent());
        Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "FX Center: Google login for saving scaletta.txt. Delete this file to log out.");
        }
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: the home directory is already private to the user
        }
        Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
