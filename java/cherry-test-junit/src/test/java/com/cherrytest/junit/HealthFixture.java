package com.cherrytest.junit;

class HealthFixture {
    @CherryTest(scope = "none")
    void demo_project_health_suite_responds_ok_test(TestEnv env) {
        env.project("demo").service("api").get("/health").send().assertStatus(200);
    }

    @CherryTest(scope = "none")
    void demo_project_health_suite_reports_a_missing_page_test(TestEnv env) {
        env.project("demo").service("api").get("/missing").send().assertStatus(200);
    }
}
