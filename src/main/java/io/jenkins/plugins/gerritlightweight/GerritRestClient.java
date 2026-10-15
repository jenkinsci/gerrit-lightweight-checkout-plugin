package io.jenkins.plugins.gerritlightweight;

import hudson.ProxyConfiguration;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.sf.json.JSONObject;

/** Small read-only Gerrit client. Redirects are disabled to protect HTTP credentials. */
final class GerritRestClient {
    /**
     * Maximum encoded response size in bytes, before base64 decoding.
     */
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    /** Gerrit context URL with the authentication prefix appended when needed. */
    private final String base;
    /** Percent-encoded project name for use as a single REST path parameter. */
    private final String project;
    /** Basic authentication header value, or {@code null} for anonymous requests. */
    private final String authorization;
    /** Transport factory used to create a fresh connection for each request. */
    private final ConnectionFactory connections;

    /**
     * Opens connections without performing a request, allowing tests to supply an in-memory transport.
     */
    @FunctionalInterface
    interface ConnectionFactory {
        /**
         * Creates a connection for a REST request.
         *
         * @param url complete endpoint URL
         * @return connection to be configured and disconnected by the client
         * @throws IOException if the connection cannot be created
         */
        HttpURLConnection open(URL url) throws IOException;
    }

    /**
     * Opens an HTTP(S) connection using the Jenkins controller proxy configuration.
     *
     * @param url complete endpoint URL
     * @return unconnected HTTP connection
     * @throws IOException if the connection cannot be opened
     */
    @SuppressWarnings("deprecation")
    private static HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) ProxyConfiguration.open(url);
    }

    /**
     * Creates a client using the Jenkins controller proxy configuration.
     *
     * @param serverUrl Gerrit HTTP(S) base URL, without credentials, query, fragment or {@code /a}
     * @param project unencoded Gerrit project name
     * @param username HTTP username, or {@code null} for anonymous access
     * @param password HTTP password or token; used only when a username is supplied
     * @throws IOException if the URL is invalid or the project is missing
     */
    GerritRestClient(String serverUrl, String project, String username, String password) throws IOException {
        this(serverUrl, project, username, password, GerritRestClient::openConnection);
    }

    /**
     * Creates a client with a supplied connection factory. Authenticated requests use Basic
     * authentication and append {@code /a} to the base URL. Construction does not perform network I/O.
     *
     * @param serverUrl Gerrit HTTP(S) base URL, without credentials, query, fragment or {@code /a}
     * @param project unencoded Gerrit project name
     * @param username HTTP username, or {@code null} for anonymous access
     * @param password HTTP password or token; used only when a username is supplied
     * @param connections factory for creating each request connection
     * @throws IOException if the URL is invalid or the project is missing
     */
    GerritRestClient(String serverUrl, String project, String username, String password, ConnectionFactory connections)
            throws IOException {
        this.connections = connections;
        String normalizedUrl = serverUrl == null ? "" : serverUrl;
        try {
            URI uri = URI.create(normalizedUrl);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            base = normalizedUrl.replaceAll("/+$", "") + (username == null ? "" : "/a");
        } catch (IllegalArgumentException e) {
            throw new IOException(Messages.GerritRestClient_InvalidServerUrl());
        }
        if (project == null || project.isBlank()) {
            throw new IOException(Messages.GerritRestClient_ProjectRequired());
        }
        this.project = encode(project);
        authorization = username == null
                ? null
                : "Basic "
                        + Base64.getEncoder()
                                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encodes one Gerrit REST path parameter, including embedded slashes and spaces.
     *
     * @param value unencoded parameter
     * @return UTF-8 percent-encoded parameter, with spaces encoded as {@code %20}
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Resolves a branch, full commit SHA or Gerrit patch-set ref to a commit SHA.
     * Strips Gerrit's XSSI prefix before parsing JSON. Patch-set commits are also checked
     * for accessibility through the configured project's commit endpoint.
     *
     * @param ref literal branch name, {@code refs/heads/} ref, 40-character SHA, or
     *     {@code refs/changes/NN/CHANGE/PATCHSET} ref
     * @return resolved 40-character commit SHA
     * @throws FileNotFoundException if Gerrit cannot find or expose the requested resource
     * @throws IOException if the ref is unsupported, the response is malformed, or a request fails
     */
    String resolve(String ref) throws IOException {
        String endpoint;
        String field;
        if (ref.matches("[0-9a-fA-F]{40}")) {
            endpoint = "/projects/" + project + "/commits/" + encode(ref);
            field = "commit";
        } else if (ref.matches("refs/changes/[0-9]{2}/[0-9]+/[0-9]+")) {
            String[] parts = ref.split("/");
            endpoint = "/changes/" + encode(parts[3]) + "/revisions/" + encode(parts[4]) + "/commit";
            field = "commit";
        } else {
            if (ref.startsWith("refs/") && !ref.startsWith("refs/heads/")) {
                throw new IOException(Messages.GerritRestClient_UnsupportedRevision());
            }
            String branch = ref.startsWith("refs/heads/") ? ref.substring(11) : ref;
            endpoint = "/projects/" + project + "/branches/" + encode(branch);
            field = "revision";
        }
        String json = new String(get(endpoint), StandardCharsets.UTF_8);
        if (json.startsWith(")]}'")) {
            int newline = json.indexOf('\n');
            if (newline < 0) throw new IOException(Messages.GerritRestClient_InvalidJson());
            json = json.substring(newline + 1);
        }
        try {
            String hash = JSONObject.fromObject(json).getString(field);
            if (!hash.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException();
            // Verify a change revision belongs to the configured project and is accessible there.
            if (ref.startsWith("refs/changes/")) {
                get("/projects/" + project + "/commits/" + hash);
            }
            return hash;
        } catch (RuntimeException e) {
            throw new IOException(Messages.GerritRestClient_InvalidRevision(), e);
        }
    }

    /**
     * Reads a file at a fixed commit and decodes Gerrit's base64 response.
     *
     * @param commit commit SHA to read
     * @param path unencoded repository-relative file path
     * @return decoded file bytes
     * @throws FileNotFoundException if the commit or file is not accessible
     * @throws IOException if the request fails, exceeds the response limit or returns invalid base64
     */
    byte[] content(String commit, String path) throws IOException {
        byte[] encoded =
                get("/projects/" + project + "/commits/" + encode(commit) + "/files/" + encode(path) + "/content");
        try {
            return Base64.getDecoder().decode(new String(encoded, StandardCharsets.US_ASCII).replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException(Messages.GerritRestClient_InvalidContent(), e);
        }
    }

    /**
     * Executes a bounded GET request with 30-second connect and read timeouts.
     * Redirects are rejected to avoid forwarding credentials. Connections are disconnected
     * after every request; response bodies are excluded from failure messages.
     *
     * @param path encoded REST path beginning with a slash, relative to the authentication base URL
     * @return response body for HTTP 200
     * @throws FileNotFoundException for HTTP 404
     * @throws IOException for other HTTP statuses, oversized responses or transport failures
     */
    private byte[] get(String path) throws IOException {
        HttpURLConnection connection = connections.open(URI.create(base + path).toURL());
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json, text/plain");
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new FileNotFoundException(Messages.GerritRestClient_NotFound());
            if (status != 200) throw new IOException(Messages.GerritRestClient_RequestFailed(status));
            try (InputStream input = connection.getInputStream()) {
                byte[] response = input.readNBytes(MAX_RESPONSE + 1);
                if (response.length > MAX_RESPONSE) throw new IOException(Messages.GerritRestClient_ResponseTooLarge());
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }
}
