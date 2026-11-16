package com.cherrytest.common.container;

import java.util.List;

/**
 * The job's private network: a holder container owns the network namespace, and the job and every image
 * service join it, so each service listens on {@code 127.0.0.1:<port>} for this job only.
 */
public record SidecarSpec(String holder, List<SidecarService> services) {
    public SidecarSpec {
        services = List.copyOf(services);
    }
}
