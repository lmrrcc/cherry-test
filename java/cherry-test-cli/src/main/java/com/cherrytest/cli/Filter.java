package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Filter {
    private Filter() {}

    public static List<ProjectGroup> applyFilter(List<ProjectGroup> projects, @Nullable String filter) {
        if (filter == null) {
            return projects;
        }
        List<ProjectGroup> byProject =
                projects.stream().filter(project -> project.id().equals(filter)).toList();
        if (!byProject.isEmpty()) {
            return byProject;
        }
        List<ProjectGroup> byPackage = new ArrayList<>();
        for (ProjectGroup project : projects) {
            List<WorkspaceMember> members = project.members().stream()
                    .filter(member -> member.name().equals(filter))
                    .toList();
            if (!members.isEmpty()) {
                byPackage.add(new ProjectGroup(project.id(), members));
            }
        }
        if (!byPackage.isEmpty()) {
            return byPackage;
        }
        List<String> projectIds = projects.stream().map(ProjectGroup::id).toList();
        List<String> packages = projects.stream()
                .flatMap(project -> project.members().stream().map(WorkspaceMember::name))
                .toList();
        throw new AnyhowError("unknown filter '" + filter + "'. Valid project groups: [" + String.join(", ", projectIds)
                + "]. Valid packages: [" + String.join(", ", packages) + "]");
    }
}
