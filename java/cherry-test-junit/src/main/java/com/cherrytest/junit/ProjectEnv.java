package com.cherrytest.junit;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.ServiceEndpoint;

public final class ProjectEnv {
    private final TestEnv env;
    private final String project;

    ProjectEnv(TestEnv env, String project) {
        this.env = env;
        this.project = project;
    }

    public ServiceEnv service(String service) {
        ServiceEndpoint endpoint = env.endpoint(project, service);
        if (!endpoint.kind().equals("http")) {
            throw new AnyhowError(
                    "service `" + project + "`.`" + service + "` is kind `" + endpoint.kind() + "`, not http");
        }
        String baseUrl = endpoint.url();
        if (baseUrl == null) {
            throw new AnyhowError("no URL for `" + project + "`.`" + service + "`");
        }
        return new ServiceEnv(env, project, service, baseUrl);
    }

    public DbEnv db(String service) {
        ServiceEndpoint endpoint = env.endpoint(project, service);
        return new DbEnv(project, service, endpoint.host(), endpoint.port(), endpoint.kind());
    }
}
