package io.jenkins.plugins.gerritlightweight;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GerritRestClientTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private final List<Response> responses = new ArrayList<>();
    private String body = ")]}'\n{\"revision\":\"" + HASH + "\"}";
    private int status = 200;

    private GerritRestClient client(String user, String password) throws IOException {
        return new GerritRestClient("https://review.example/gerrit/", "team/service", user, password, request -> {
            Response response = new Response(request, body);
            responses.add(response);
            return new TestResponse(status, request, response.input);
        });
    }

    @Test
    void resolvesBranchThenReadsPinnedNestedScript() throws Exception {
        GerritRestClient client = client(null, null);
        String commit = client.resolve("refs/heads/feature/topic");
        body = Base64.getEncoder().encodeToString("echo 'héllo'".getBytes(StandardCharsets.UTF_8)) + "\n";
        assertEquals("echo 'héllo'", new String(client.content(commit, "ci/Jenkinsfile"), StandardCharsets.UTF_8));
        assertEquals(
                "https://review.example/gerrit/projects/team%2Fservice/branches/feature%2Ftopic",
                responses.get(0).request.uri().toString());
        assertEquals(
                "https://review.example/gerrit/projects/team%2Fservice/commits/" + HASH
                        + "/files/ci%2FJenkinsfile/content",
                responses.get(1).request.uri().toString());
        assertTrue(responses.stream().allMatch(response -> response.closed));
    }

    @Test
    void authenticatesWithoutFollowingRedirects() throws Exception {
        client("user", "token").resolve("main");
        Response response = responses.get(0);
        assertEquals(
                "Basic dXNlcjp0b2tlbg==",
                response.request.headers().firstValue("Authorization").orElseThrow());
        assertTrue(response.request.uri().getRawPath().startsWith("/gerrit/a/"));
        assertEquals(Duration.ofSeconds(30), response.request.timeout().orElseThrow());
        assertEquals("GET", response.request.method());
    }

    @Test
    void resolvesPatchSetsAndCommitHashes() throws Exception {
        body = ")]}'\n{\"commit\":\"" + HASH + "\"}";
        GerritRestClient client = client(null, null);
        assertEquals(HASH, client.resolve("refs/changes/34/1234/5"));
        assertEquals(
                "/gerrit/changes/1234/revisions/5/commit",
                responses.get(0).request.uri().getRawPath());
        assertTrue(responses.get(1).request.uri().toString().contains("/projects/team%2Fservice/commits/" + HASH));
        assertEquals(HASH, client.resolve(HASH));
    }

    @Test
    void reportsMissingAndFailedRequestsWithoutResponseBody() throws Exception {
        GerritRestClient client = client(null, null);
        status = 404;
        assertThrows(FileNotFoundException.class, () -> client.content(HASH, "Jenkinsfile"));
        body = "secret error details";
        for (int code : new int[] {301, 401, 403, 500}) {
            status = code;
            IOException error = assertThrows(IOException.class, () -> client.content(HASH, "Jenkinsfile"));
            assertTrue(error.getMessage().contains(Integer.toString(code)));
            assertFalse(error.getMessage().contains(body));
        }
        assertTrue(responses.stream().allMatch(response -> response.closed));
    }

    @Test
    void rejectsMalformedAndOversizedResponses() throws Exception {
        GerritRestClient client = client(null, null);
        body = "%%%";
        assertThrows(IOException.class, () -> client.content(HASH, "Jenkinsfile"));
        body = "{\"revision\":\"not-a-hash\"}";
        assertThrows(IOException.class, () -> client.resolve("main"));
        body = "x".repeat(8 * 1024 * 1024 + 1);
        assertThrows(IOException.class, () -> client.content(HASH, "Jenkinsfile"));
    }

    @Test
    void validatesUrlsAndBranchSelection() throws Exception {
        for (String url : new String[] {
            "ssh://review.example",
            "https://user:token@review.example",
            "https://review.example?token=secret",
            "https://review.example#fragment",
            ""
        }) {
            assertThrows(IOException.class, () -> new GerritRestClient(url, "p", null, null));
        }
        assertEquals("feature/topic", GerritSCMFileSystem.BuilderImpl.normalizeRef("*/feature/topic"));
        for (String ref : new String[] {"*", "*/feature/*", "${BRANCH}", ":regex", ""}) {
            assertThrows(IOException.class, () -> GerritSCMFileSystem.BuilderImpl.normalizeRef(ref));
        }
        assertThrows(IOException.class, () -> client(null, null).resolve("refs/tags/v1"));
        assertEquals("a%2Fb%20c%2B%25", GerritRestClient.encode("a/b c+%"));
    }

    @Test
    void rejectsRedirectsWithRealTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean followed = new AtomicBoolean();
        server.createContext("/a/projects/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            followed.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            GerritRestClient client = new GerritRestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "p", "user", "token");
            IOException error = assertThrows(IOException.class, () -> client.resolve("main"));
            assertTrue(error.getMessage().contains("302"));
            assertFalse(followed.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restoresInterruptFlag() throws Exception {
        GerritRestClient client = new GerritRestClient("https://review.example", "p", null, null, request -> {
            throw new InterruptedException("interrupted");
        });
        try {
            IOException error = assertThrows(IOException.class, () -> client.resolve("main"));
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private record TestResponse(int statusCode, HttpRequest request, InputStream body)
            implements HttpResponse<InputStream> {
        @Override
        public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class Response {
        private final HttpRequest request;
        private final InputStream input;
        private boolean closed;

        Response(HttpRequest request, String body) {
            this.request = request;
            this.input = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }
    }
}
