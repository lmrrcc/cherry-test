package com.cherrytest.junit;

public final class ServiceEnv {
    private final TestEnv env;
    private final String targetProject;
    private final String targetService;
    private final String baseUrl;

    ServiceEnv(TestEnv env, String targetProject, String targetService, String baseUrl) {
        this.env = env;
        this.targetProject = targetProject;
        this.targetService = targetService;
        this.baseUrl = baseUrl;
    }

    public String url() {
        return baseUrl;
    }

    public ServiceRequest get(String path) {
        return new ServiceRequest(env, targetProject, targetService, baseUrl, "GET", path);
    }

    public ServiceRequest post(String path) {
        return new ServiceRequest(env, targetProject, targetService, baseUrl, "POST", path);
    }
}
