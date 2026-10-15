package io.jenkins.plugins.gerritlightweight;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Explicitly opts a Git SCM into REST-based Pipeline script retrieval. */
public class GerritRestCheckout extends GitSCMExtension {
    /** Trimmed Gerrit base URL, or {@code null} when omitted from job configuration. */
    private final String serverUrl;
    /** Unencoded Gerrit project name, or {@code null} when omitted. */
    private final String project;
    /** HTTP credential ID, or {@code null} for anonymous requests. */
    private final String credentialsId;

    /**
     * Creates the Git behavior from job configuration.
     *
     * @param serverUrl Gerrit HTTP(S) base URL, including any context path but excluding {@code /a}
     * @param project unencoded Gerrit project name, which must match the configured Git remote
     * @param credentialsId optional username/password credential ID; blank selects anonymous access
     */
    @DataBoundConstructor
    public GerritRestCheckout(String serverUrl, String project, String credentialsId) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
        this.project = Util.fixEmptyAndTrim(project);
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    /**
     * Returns the configured Gerrit base URL.
     *
     * @return trimmed URL, or {@code null} if not configured
     */
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * Returns the project name used by the Gerrit REST endpoints.
     *
     * @return unencoded project name, or {@code null} if not configured
     */
    public String getProject() {
        return project;
    }

    /**
     * Returns the HTTP credential ID, independently of the Git remote credentials.
     *
     * @return credential ID, or {@code null} for anonymous REST requests
     */
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * Registers the Git behavior and supplies its configuration UI.
     */
    @Extension
    @Symbol("gerritRestCheckout")
    public static class DescriptorImpl extends GitSCMExtensionDescriptor {
        @Override
        @NonNull
        public String getDisplayName() {
            return Messages.GerritRestCheckout_DisplayName();
        }

        /**
         * Populates the credential selector with username/password credentials matching the server URL.
         * Callers without job configuration permission (or administrator permission outside a job)
         * receive only the current selection, without enumerating other credentials.
         *
         * @param item owning job, or {@code null} outside a job context
         * @param serverUrl Gerrit URL used to restrict credential domains
         * @param credentialsId currently selected credential ID
         * @return available credential choices, including the current selection
         */
        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String serverUrl, @QueryParameter String credentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            if (item == null ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER) : !item.hasPermission(Item.CONFIGURE)) {
                return model.includeCurrentValue(credentialsId);
            }
            return model.includeEmptyValue()
                    .includeAs(
                            ACL.SYSTEM2,
                            item,
                            StandardUsernamePasswordCredentials.class,
                            URIRequirementBuilder.fromUri(serverUrl).build())
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public FormValidation doCheckServerUrl(@QueryParameter String serverUrl) {
            return Utilities.validateUrl(serverUrl);
        }
    }
}
