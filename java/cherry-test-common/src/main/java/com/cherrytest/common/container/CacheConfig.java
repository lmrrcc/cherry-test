package com.cherrytest.common.container;

import java.util.List;

/** A named Docker volume kept between runs, such as a dependency cache. */
public record CacheConfig(String volume, String target) {
    public static final List<String> FIELDS = List.of("volume", "target");
}
