package io.jenkins.plugins.gerritlightweight;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class GerritRestClientTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private final List<Response> responses = new ArrayList<>();
    private String body = ")]}'\n{\"revision\":\"" + HASH + "\"}";
    private int status = 200;

    private GerritRestClient client(String user, String password) throws IOException {
        return new GerritRestClient("https://review.example/gerrit/", "team/service", user, password, url -> {
            Response response = new Response(url, status, body);
            responses.add(response);
            return response;
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
                responses.get(0).getURL().toString());
        assertEquals(
                "https://review.example/gerrit/projects/team%2Fservice/commits/" + HASH
                        + "/files/ci%2FJenkinsfile/content",
                responses.get(1).getURL().toString());
        assertTrue(responses.stream().allMatch(response -> response.disconnected));
    }

    @Test
    void authenticatesWithoutFollowingRedirects() throws Exception {
        client("user", "token").resolve("main");
        Response response = responses.get(0);
        assertEquals("Basic dXNlcjp0b2tlbg==", response.getRequestProperty("Authorization"));
        assertTrue(response.getURL().getPath().startsWith("/gerrit/a/"));
        assertFalse(response.getInstanceFollowRedirects());
        assertEquals(30_000, response.getConnectTimeout());
        assertEquals(30_000, response.getReadTimeout());
    }

    @Test
    void resolvesPatchSetsAndCommitHashes() throws Exception {
        body = ")]}'\n{\"commit\":\"" + HASH + "\"}";
        GerritRestClient client = client(null, null);
        assertEquals(HASH, client.resolve("refs/changes/34/1234/5"));
        assertEquals(
                "/gerrit/changes/1234/revisions/5/commit",
                responses.get(0).getURL().getPath());
        assertTrue(responses.get(1).getURL().toString().contains("/projects/team%2Fservice/commits/" + HASH));
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
        assertTrue(responses.stream().allMatch(response -> response.disconnected));
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

    private static final class Response extends HttpURLConnection {
        private final int status;
        private final byte[] bytes;
        private boolean disconnected;

        Response(URL url, int status, String body) {
            super(url);
            this.status = status;
            this.bytes = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}
    }
}
