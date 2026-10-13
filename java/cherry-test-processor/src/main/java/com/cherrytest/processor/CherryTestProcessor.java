package com.cherrytest.processor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;

public final class CherryTestProcessor extends AbstractProcessor {
    static final String ANNOTATION = "com.cherrytest.junit.CherryTest";
    static final String TEST_ENV = "com.cherrytest.junit.TestEnv";

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ANNOTATION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnvironment.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.METHOD) {
                    check((ExecutableElement) element)
                            .ifPresent(message ->
                                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element));
                }
            }
        }
        return false;
    }

    static Optional<String> check(ExecutableElement method) {
        Optional<String> naming = TestNames.violation(method.getSimpleName().toString());
        if (naming.isPresent()) {
            return naming;
        }
        if (method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.PRIVATE)) {
            return Optional.of("cherry-test integration tests must be non-private instance methods");
        }
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.size() != 1 || !parameters.getFirst().asType().toString().equals(TEST_ENV)) {
            return Optional.of("cherry-test integration tests must take a single `env: TestEnv` parameter");
        }
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            return Optional.of("cherry-test integration tests must not declare a return type");
        }
        return Optional.empty();
    }
}
