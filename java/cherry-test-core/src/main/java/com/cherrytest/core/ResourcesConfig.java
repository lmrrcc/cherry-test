package com.cherrytest.core;

import org.jspecify.annotations.Nullable;

/** {@code packages.<name>.resources}: what one job of the package reserves while it runs. */
public record ResourcesConfig(
        @Nullable Long millicpus, @Nullable Long memory) {}
