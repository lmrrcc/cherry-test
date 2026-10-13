package com.cherrytest.cli;

import com.cherrytest.core.PackageRuntime;
import java.nio.file.Path;

public record WorkspaceMember(String name, Path manifestPath, String projectId, PackageRuntime runtime) {}
