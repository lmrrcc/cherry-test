package com.cherrytest.common.container;

import java.util.List;
import java.util.stream.Collectors;

/** The serde wording for the accepted names in unknown-field and unknown-variant errors. */
public final class Expected {
    private Expected() {}

    public static String oneOf(List<String> names) {
        return switch (names.size()) {
            case 0 -> "there are no fields";
            case 1 -> "expected `" + names.getFirst() + "`";
            case 2 -> "expected `" + names.get(0) + "` or `" + names.get(1) + "`";
            default ->
                "expected one of "
                        + names.stream().map(name -> "`" + name + "`").collect(Collectors.joining(", "));
        };
    }

    public static String unknownField(String field, List<String> names) {
        return "unknown field `" + field + "`, " + oneOf(names);
    }

    public static String unknownVariant(String variant, List<String> names) {
        return "unknown variant `" + variant + "`, " + oneOf(names);
    }
}
