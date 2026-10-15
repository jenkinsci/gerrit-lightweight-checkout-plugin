package io.jenkins.plugins.gerritlightweight;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpServer;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import jenkins.scm.api.SCMFileSystem;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

class GerritCheckoutTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private HttpServer server;
    private String url;
    private final List<String> requests = new ArrayList<>();
    private String auth;
    private String observedAuth;
    private int status = 200;
    private String encodedContent;

    @BeforeEach
    void start() throws IOException {
        encodedContent =
                Base64.getEncoder().encodeToString("echo 'REST script executed'".getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gerrit/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            synchronized (requests) {
                requests.add(path);
            }
            observedAuth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = path.endsWith("/content")
                    ? encodedContent
                    : path.contains("/branches/")
                            ? ")]}'\n{\"revision\":\"" + HASH + "\"}"
                            : ")]}'\n{\"commit\":\"" + HASH + "\"}";
            int code = auth != null && !auth.equals(observedAuth) ? 401 : status;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/gerrit";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private GitSCM scm(String credentialsId) {
        return new GitSCM(
                List.of(new UserRemoteConfig("ssh://invalid.example/team/service", null, null, null)),
                List.of(new BranchSpec("*/feature/topic")),
                null,
                null,
                List.of(new GerritRestCheckout(url, "team/service", credentialsId)));
    }

    @Test
    @WithJenkins
    void pipelineUsesRestWithoutGitCheckout(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "rest-pipeline");
        CpsScmFlowDefinition definition = new CpsScmFlowDefinition(scm(null), "ci/Jenkinsfile");
        definition.setLightweight(true);
        job.setDefinition(definition);
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("REST script executed", run);
        assertEquals(
                List.of(
                        "/gerrit/projects/team%2Fservice/branches/feature%2Ftopic",
                        "/gerrit/projects/team%2Fservice/commits/" + HASH + "/files/ci%2FJenkinsfile/content"),
                requests);
    }

    @Test
    @WithJenkins
    void authenticatesAndPinsFileReads(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "gerrit-http", "", "user", "token"));
        auth = "Basic " + Base64.getEncoder().encodeToString("user:token".getBytes(StandardCharsets.UTF_8));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        try (SCMFileSystem fs = SCMFileSystem.of(job, scm("gerrit-http"))) {
            assertInstanceOf(GerritSCMFileSystem.class, fs);
            assertTrue(fs.isFixedRevision());
            assertEquals(
                    "echo 'REST script executed'",
                    fs.getRoot().child("ci/Jenkinsfile").contentAsString());
        }
        assertEquals(auth, observedAuth);
        assertTrue(requests.stream().allMatch(path -> path.startsWith("/gerrit/a/")));
    }

    @Test
    void patchSetResolvesAndChecksConfiguredProject() throws Exception {
        GerritRestClient client = new GerritRestClient(url, "team/service", null, null);
        assertEquals(HASH, client.resolve("refs/changes/34/1234/5"));
        assertEquals(
                List.of("/gerrit/changes/1234/revisions/5/commit", "/gerrit/projects/team%2Fservice/commits/" + HASH),
                requests);
    }

    @Test
    void distinguishesMissingFilesFromAuthorizationFailures() throws Exception {
        GerritRestClient client = new GerritRestClient(url, "team/service", null, null);
        status = 404;
        assertThrows(FileNotFoundException.class, () -> client.content(HASH, "Jenkinsfile"));
        status = 403;
        IOException failure = assertThrows(IOException.class, () -> client.content(HASH, "Jenkinsfile"));
        assertFalse(failure instanceof FileNotFoundException);
        assertTrue(failure.getMessage().contains("403"));
    }

    @Test
    void rejectsInvalidContentRedirectsAndAmbiguousRefs() throws Exception {
        GerritRestClient client = new GerritRestClient(url, "team/service", null, null);
        encodedContent = "%%%";
        assertThrows(IOException.class, () -> client.content(HASH, "Jenkinsfile"));
        status = 302;
        assertThrows(IOException.class, () -> client.content(HASH, "Jenkinsfile"));
        assertThrows(IOException.class, () -> GerritSCMFileSystem.BuilderImpl.normalizeRef("*/feature/*"));
        assertThrows(IOException.class, () -> GerritSCMFileSystem.BuilderImpl.normalizeRef("${BRANCH}"));
        assertThrows(IOException.class, () -> new GerritRestClient("https://user:token@example.com", "p", null, null));
    }

    @Test
    @WithJenkins
    void configurationSurvivesRoundTrip(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject();
        project.setScm(scm(null));
        j.configRoundtrip(project);
        GerritRestCheckout config = ((GitSCM) project.getScm()).getExtensions().get(GerritRestCheckout.class);
        assertNotNull(config);
        assertEquals(url, config.getServerUrl());
        assertEquals("team/service", config.getProject());
    }
}
