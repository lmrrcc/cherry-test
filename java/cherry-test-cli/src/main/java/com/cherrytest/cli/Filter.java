package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class Filter {
    private Filter() {}

    public static List<ProjectGroup> applyFilter(List<ProjectGroup> projects, @Nullable String filter) {
        return applyFilter(projects, filter, Map.of());
    }

    /** Selects a project id, a package name or, with {@code @name}, the packages of a configured group. */
    public static List<ProjectGroup> applyFilter(
            List<ProjectGroup> projects, @Nullable String filter, Map<String, List<String>> groups) {
        if (filter == null) {
            return projects;
        }
        if (filter.startsWith("@")) {
            return selectGroup(projects, filter.substring(1), groups);
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

    /** The packages a group names, exactly or by {@code *}/{@code ?} glob, kept in configuration order. */
    private static List<ProjectGroup> selectGroup(
            List<ProjectGroup> projects, String group, Map<String, List<String>> groups) {
        List<String> patterns = groups.get(group);
        if (patterns == null) {
            List<String> names = new ArrayList<>(groups.keySet());
            names.sort(RustOrdering.STRING_ORDER);
            throw new AnyhowError("unknown group '@" + group + "'. Valid groups: ["
                    + String.join(", ", names.stream().map(name -> "@" + name).toList()) + "]");
        }
        List<String> packages = projects.stream()
                .flatMap(project -> project.members().stream().map(WorkspaceMember::name))
                .toList();
        for (String pattern : patterns) {
            boolean glob = pattern.contains("*") || pattern.contains("?");
            if (!glob && !packages.contains(pattern)) {
                throw new AnyhowError("group '@" + group + "' names unknown package '" + pattern + "'");
            }
        }
        List<ProjectGroup> selected = new ArrayList<>();
        for (ProjectGroup project : projects) {
            List<WorkspaceMember> members = project.members().stream()
                    .filter(member ->
                            patterns.stream().anyMatch(pattern -> Preflight.globMatches(pattern, member.name())))
                    .toList();
            if (!members.isEmpty()) {
                selected.add(new ProjectGroup(project.id(), members));
            }
        }
        if (selected.isEmpty()) {
            throw new AnyhowError("group '@" + group + "' matches no packages");
        }
        return selected;
    }
}
