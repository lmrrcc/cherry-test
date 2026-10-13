package com.cherrytest.common.gitdiff;

import com.cherrytest.common.config.ReviewChangedScope;
import java.nio.file.Path;
import java.util.List;

public record ChangedFilesProject(
        String id,
        String diffBase,
        ReviewChangedScope changedScope,
        List<String> packagePrefixes,
        Path gitRoot,
        Path workspaceRoot,
        String pathPrefix,
        List<NestedGitScope> gitScopes) {
    public ChangedFilesProject {
        packagePrefixes = List.copyOf(packagePrefixes);
        gitScopes = List.copyOf(gitScopes);
    }
}
