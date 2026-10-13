package com.cherrytest.common.gitdiff;

import java.nio.file.Path;

public record NestedGitScope(String moduleId, Path gitRoot, String pathPrefix) {}
