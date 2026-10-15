package io.jenkins.plugins.gerritlightweight;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.security.ACL;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;

/** A commit-pinned, REST-backed view for reading a Pipeline script. */
public final class GerritSCMFileSystem extends SCMFileSystem {
    /** Client used for reads from the configured Gerrit project. */
    private final GerritRestClient client;
    /** Resolved commit SHA shared by every file in this view. */
    private final String commit;

    /**
     * Creates a filesystem by resolving the requested ref once. All subsequent file reads
     * use the resolved commit, even if the branch advances.
     *
     * @param client configured Gerrit REST client
     * @param ref branch, commit SHA or patch-set ref accepted by the client
     * @throws IOException if Gerrit cannot resolve the ref
     */
    GerritSCMFileSystem(GerritRestClient client, String ref) throws IOException {
        this(client, ref, client.resolve(ref));
    }

    /**
     * Creates a fixed Git SCM revision for an already resolved commit.
     *
     * @param client configured Gerrit REST client
     * @param ref original ref used as the SCM head name
     * @param commit resolved commit SHA
     */
    private GerritSCMFileSystem(GerritRestClient client, String ref, String commit) {
        super(new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead(ref), commit));
        this.client = client;
        this.commit = commit;
    }

    /**
     * Returns zero because filesystem modification times are unsupported.
     *
     * @return {@code 0}
     */
    @Override
    public long lastModified() {
        return 0;
    }

    /**
     * Releases no resources; the client disconnects each HTTP request immediately after reading it.
     */
    @Override
    public void close() {}

    /**
     * Returns the root of the commit-pinned file view.
     *
     * @return root supporting repository-relative file reads
     */
    @NonNull
    @Override
    public SCMFile getRoot() {
        return new RestFile();
    }

    /**
     * A lazily loaded file whose decoded bytes are cached for repeated reads.
     * Root and intermediate path entries are treated as directories without querying Gerrit.
     */
    private final class RestFile extends SCMFile {
        /** Whether this entry is the root or an assumed intermediate directory. */
        private final boolean directory;
        /** Decoded file bytes, loaded on first successful read. */
        private byte[] data;

        /**
         * Creates the virtual repository root.
         */
        RestFile() {
            directory = true;
        }

        /**
         * Creates a child entry without loading its content.
         *
         * @param parent parent entry
         * @param name single path component
         * @param directory whether path traversal identifies this entry as an intermediate directory
         */
        RestFile(SCMFile parent, String name, boolean directory) {
            super(parent, name);
            this.directory = directory;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        protected SCMFile newChild(@NonNull String name, boolean assumeIsDirectory) {
            return new RestFile(this, name, assumeIsDirectory);
        }

        /**
         * Directory enumeration is unsupported by this file-reading view.
         *
         * @return never returns normally
         * @throws UnsupportedOperationException always
         */
        @NonNull
        @Override
        public Iterable<SCMFile> children() {
            throw new UnsupportedOperationException(Messages.GerritSCMFileSystem_DirectoryListingUnsupported());
        }

        /**
         * Returns zero because file modification times are unsupported.
         *
         * @return {@code 0}
         */
        @Override
        public long lastModified() {
            return 0;
        }

        /**
         * Determines a leaf entry's type by attempting to read it. Only HTTP 404 is treated
         * as a nonexistent entry; authentication and other failures propagate to the caller.
         *
         * @return directory for a path hint, regular file for readable content, or nonexistent for HTTP 404
         * @throws IOException if a file request fails for a reason other than HTTP 404
         */
        @NonNull
        @Override
        protected Type type() throws IOException {
            if (directory) return Type.DIRECTORY;
            try {
                load();
                return Type.REGULAR_FILE;
            } catch (FileNotFoundException e) {
                return Type.NONEXISTENT;
            }
        }

        /**
         * Loads and caches this entry's decoded content at the filesystem's fixed commit.
         *
         * @return cached file bytes
         * @throws IOException if Gerrit cannot supply valid content
         */
        private byte[] load() throws IOException {
            if (data == null) data = client.content(commit, getPath());
            return data;
        }

        /**
         * Opens an independent stream over the cached file bytes.
         *
         * @return new stream positioned at the start of the file
         * @throws IOException if this entry is a directory or its content cannot be read
         */
        @NonNull
        @Override
        public InputStream content() throws IOException {
            if (directory) throw new IOException(Messages.GerritSCMFileSystem_CannotReadDirectory());
            return new ByteArrayInputStream(load());
        }
    }

    /**
     * Selects REST retrieval for Git SCMs explicitly configured with {@link GerritRestCheckout}.
     * The extension ordinal gives this builder priority over the default Git provider.
     * SCM source discovery is unsupported.
     */
    @Extension(ordinal = 100)
    public static final class BuilderImpl extends SCMFileSystem.Builder {
        /**
         * Checks whether a Git SCM explicitly enables this REST provider.
         *
         * @param scm SCM configuration to inspect
         * @return whether the Gerrit REST behavior is present
         */
        @Override
        public boolean supports(SCM scm) {
            return scm instanceof GitSCM git && git.getExtensions().get(GerritRestCheckout.class) != null;
        }

        /**
         * Reports that this builder does not directly support SCM sources.
         *
         * @param source SCM source to inspect
         * @return {@code false}
         */
        @Override
        public boolean supports(SCMSource source) {
            return false;
        }

        /**
         * Identifies Git SCM descriptors as potentially supported; individual jobs must opt in.
         *
         * @param descriptor SCM descriptor to inspect
         * @return whether the descriptor belongs to Git SCM
         */
        @Override
        protected boolean supportsDescriptor(SCMDescriptor descriptor) {
            return descriptor instanceof GitSCM.DescriptorImpl;
        }

        /**
         * Reports that SCM source descriptors are unsupported.
         *
         * @param descriptor SCM source descriptor to inspect
         * @return {@code false}
         */
        @Override
        protected boolean supportsDescriptor(SCMSourceDescriptor descriptor) {
            return false;
        }

        /**
         * Builds a REST filesystem without a build environment for variable expansion.
         *
         * @param owner item owning the SCM configuration and credential scope
         * @param scm SCM configuration
         * @param revision requested Git revision, or {@code null} to resolve the configured branch
         * @return fixed REST filesystem, or {@code null} for an unsupported SCM or revision type
         * @throws IOException if configuration, credential lookup or ref resolution fails
         * @throws InterruptedException if filesystem initialization is interrupted
         */
        @Override
        public SCMFileSystem build(@NonNull Item owner, @NonNull SCM scm, SCMRevision revision)
                throws IOException, InterruptedException {
            return build(owner, scm, revision, null);
        }

        /**
         * Builds a fixed REST filesystem using job-scoped HTTP credentials. Requires one Git
         * remote and one branch. A supplied Git revision takes precedence over the branch; otherwise,
         * branch variables are expanded from the build environment when available.
         *
         * @param owner item owning the SCM configuration and credential scope
         * @param scm SCM configuration
         * @param revision requested Git revision, or {@code null} to resolve the configured branch
         * @param run optional build context for branch expansion and credential lookup
         * @return fixed REST filesystem, or {@code null} for an unsupported SCM or revision type
         * @throws IOException if configuration, credential lookup, environment loading or ref resolution fails
         * @throws InterruptedException if obtaining the build environment is interrupted
         */
        @Override
        public SCMFileSystem build(@NonNull Item owner, @NonNull SCM scm, SCMRevision revision, Run<?, ?> run)
                throws IOException, InterruptedException {
            if (!(scm instanceof GitSCM git) || !supports(scm)) return null;
            GerritRestCheckout config = git.getExtensions().get(GerritRestCheckout.class);
            if (config == null) return null;
            if (git.getUserRemoteConfigs().size() != 1 || git.getBranches().size() != 1) {
                throw new IOException(Messages.GerritSCMFileSystem_SingleRemoteAndBranchRequired());
            }
            String ref;
            if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl gitRevision) {
                ref = gitRevision.getHash();
            } else if (revision != null) {
                return null;
            } else {
                ref = git.getBranches().get(0).getName();
                if (run != null) ref = run.getEnvironment(TaskListener.NULL).expand(ref);
                ref = normalizeRef(ref);
            }
            StandardUsernamePasswordCredentials credentials = null;
            if (config.getCredentialsId() != null) {
                credentials = run == null
                        ? CredentialsProvider.findCredentialByIdInItem(
                                config.getCredentialsId(),
                                StandardUsernamePasswordCredentials.class,
                                owner,
                                ACL.SYSTEM2,
                                URIRequirementBuilder.fromUri(config.getServerUrl())
                                        .build())
                        : CredentialsProvider.findCredentialById(
                                config.getCredentialsId(),
                                StandardUsernamePasswordCredentials.class,
                                run,
                                URIRequirementBuilder.fromUri(config.getServerUrl())
                                        .build());
                if (credentials == null) throw new IOException(Messages.GerritSCMFileSystem_CredentialsUnavailable());
                CredentialsProvider.track(owner, credentials);
            }
            return new GerritSCMFileSystem(
                    new GerritRestClient(
                            config.getServerUrl(),
                            config.getProject(),
                            credentials == null ? null : credentials.getUsername(),
                            credentials == null
                                    ? null
                                    : credentials.getPassword().getPlainText()),
                    ref);
        }

        /**
         * Removes Git's conventional {@code *}/ branch prefix and rejects ambiguous selection.
         * Other ref forms are validated by the REST client during resolution.
         *
         * @param ref expanded Git branch specification
         * @return literal ref without the conventional remote wildcard prefix
         * @throws IOException if the ref is blank, contains wildcards or unresolved variables, or is a regex
         */
        static String normalizeRef(String ref) throws IOException {
            if (ref.startsWith("*/")) ref = ref.substring(2);
            if (ref.isBlank() || ref.contains("*") || ref.contains("?") || ref.contains("$") || ref.startsWith(":")) {
                throw new IOException(Messages.GerritSCMFileSystem_LiteralRefRequired());
            }
            return ref;
        }
    }
}
