package com.cherrytest.common.container;

import java.util.List;

/**
 * How Cherry Test decides that an image service is ready: a command inside the service container that exits
 * with status 0, or, when {@code exec} is empty, a socket listening on the port.
 */
public record HealthCheck(List<String> exec, long timeoutSecs) {
    public HealthCheck {
        exec = List.copyOf(exec);
    }
}
