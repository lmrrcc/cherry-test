package com.cherrytest.junit;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGenerator;

public final class CherryTestDisplayNames extends DisplayNameGenerator.Standard {
    public CherryTestDisplayNames() {}

    @Override
    public String generateDisplayNameForMethod(
            List<Class<?>> enclosingInstanceTypes, Class<?> testClass, Method testMethod) {
        return testMethod.isAnnotationPresent(CherryTest.class)
                ? testMethod.getName()
                : super.generateDisplayNameForMethod(enclosingInstanceTypes, testClass, testMethod);
    }
}
