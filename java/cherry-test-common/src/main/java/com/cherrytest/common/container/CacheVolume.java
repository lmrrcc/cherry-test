package com.cherrytest.common.container;

/** A named volume mounted into the container. */
public record CacheVolume(String volume, String target) {}
