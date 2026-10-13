package com.cherrytest.cli;

import java.util.List;

public record ProjectGroup(String id, List<WorkspaceMember> members) {
    public ProjectGroup {
        members = List.copyOf(members);
    }
}
