package com.cherrytest.cli.adapters;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

final class SignalCancellation {
    private SignalCancellation() {}

    static void install(List<String> signals, Runnable onSignal) {
        try {
            Class<?> signalType = Class.forName("sun.misc.Signal");
            Class<?> handlerType = Class.forName("sun.misc.SignalHandler");
            Constructor<?> constructor = signalType.getConstructor(String.class);
            Method handle = signalType.getMethod("handle", signalType, handlerType);
            Object handler = Proxy.newProxyInstance(
                    handlerType.getClassLoader(), new Class<?>[] {handlerType}, (proxy, method, arguments) -> {
                        if (method.getName().equals("handle")) {
                            onSignal.run();
                        }
                        return null;
                    });
            for (String name : signals) {
                handle.invoke(null, constructor.newInstance(name), handler);
            }
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception instanceof InvocationTargetException target && target.getCause() != null
                    ? target.getCause()
                    : exception;
            throw new AnyhowError("install signal handlers: " + cause.getMessage(), cause);
        }
    }
}
