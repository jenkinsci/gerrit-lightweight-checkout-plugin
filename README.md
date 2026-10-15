# Gerrit Lightweight Checkout

Fetch a Pipeline's Jenkinsfile through the Gerrit REST API instead of fetching the Git repository on the Jenkins controller. This plugin adds an optional Git SCM behavior for **Pipeline script from SCM** jobs with **Lightweight checkout** enabled.

## Configure a job

1. Install the plugin and its Git, SCM API, and Credentials dependencies.
2. In a Pipeline job, choose **Pipeline script from SCM**, then **Git**. Configure the repository URL and Git credentials as usual.
3. Configure exactly one remote and one branch. Supported values include `*/main`, `refs/heads/feature/topic`, a full 40-character commit SHA, and Gerrit patch-set refs such as `refs/changes/34/1234/5`. You can also use variables that are provided by a plugin the gives this information (e.g., the Gerrit Trigger Plugin).
4. Under **Additional Behaviours**, add **Gerrit REST lightweight checkout**.
5. Enter the Gerrit web URL (for example `https://review.example.com/gerrit`) and exact project name (for example `team/service`). The project must match the Git repository. Do not include `/a` in the URL or URL-encode the project.
6. Select an optional **username/password** credential containing your Gerrit username and HTTP password/token. REST credentials are separate from Git SSH credentials. Leave this empty for anonymous access.
7. Set **Script Path** to `Jenkinsfile` or a nested path such as `ci/Jenkinsfile`, and enable **Lightweight checkout**.

The Jenkins controller needs HTTP(S) access to Gerrit. Authenticated requests use Gerrit's `/a` prefix and HTTP Basic authentication; use HTTPS for authenticated servers. Jenkins proxy configuration is honored. Redirects are rejected, so configure the final Gerrit URL.

## Behavior and limitations

The plugin first resolves the configured branch or patch set to a commit, then reads the script from that commit's file-content endpoint and decodes Gerrit's base64 response. Reading the script does not require Git or a controller-side repository cache. Git still handles workspace checkout, polling, and changelogs; a subsequent `checkout scm` can observe a newer branch tip.

Build environment variables in branch specifications are expanded when Jenkins supplies a build context, including a Gerrit-triggered job configured with `${GERRIT_REFSPEC}`. Unresolved variables, wildcard branch selection, multiple remotes/branches, and tag refs are unsupported. This integration targets ordinary Pipeline-from-SCM jobs, not multibranch discovery or a Gerrit SCMSource. Directory listing and REST-based changelogs are unsupported.

The REST provider takes priority over Git's built-in lightweight provider for jobs with this behavior. Jenkins' SCM API can try another provider if REST filesystem initialization fails, so a REST connection or configuration failure may cause Git's lightweight provider to fetch a repository. Failures reading a file after REST initialization (including missing files or authorization failures) are reported as errors.

Requests use 30-second connect/read timeouts and an 8 MiB limit per response. Missing resources return file-not-found errors; authentication and server failures preserve the HTTP status without printing response bodies or credentials.

## Build and test

```sh
mvn verify
```

Install `target/gerrit-lightweight-checkout.hpi` through Jenkins' advanced plugin installation page. For development, use `mvn hpi:run`.

Tests include a real Pipeline-from-SCM build against a local Gerrit HTTP fixture with an unreachable Git remote, authenticated credential lookup, nested script paths, patch-set resolution, error handling, and configuration persistence.

The implementation follows the [Jenkins SCM API](https://javadoc.jenkins.io/plugin/scm-api/jenkins/scm/api/SCMFileSystem.html), Gerrit's [project REST endpoints](https://gerrit-review.googlesource.com/Documentation/rest-api-projects.html), and its [REST authentication conventions](https://gerrit-review.googlesource.com/Documentation/rest-api.html).

## License

[MIT](LICENSE.md)
