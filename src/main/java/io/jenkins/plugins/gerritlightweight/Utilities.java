package io.jenkins.plugins.gerritlightweight;

import hudson.util.FormValidation;
import java.net.URI;

public class Utilities {

    public static FormValidation validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return FormValidation.error(Messages.Util_MissingUrlInformation());
            }
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e, Messages.Util_InvalidUrl());
        }

        return FormValidation.ok();
    }
}
