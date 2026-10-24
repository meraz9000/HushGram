/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import app.hushgram.extension.shared.Logger;

/**
 * The continuation a gallery hand-off gives Instagram's suspending capture call, built at run
 * time because Instagram's build renames Kotlin's own coroutine types.
 *
 * <p>The patch supplies the interface's dex name, and a dynamic proxy over whichever interface
 * that is answers the call's questions: the context is another proxy that reports nothing held,
 * and a resume reports only a failure. The call's own state machine wraps this and does the real
 * resuming, so the capture runs exactly as a shutter press's and nothing waits on the caller.
 * Built once per call; any failure answers null, and the hand-off then refuses instead of
 * running a call that could crash the app.
 */
public final class InstantsContinuation {
    private static final String SOURCE = "InstantsContinuation";

    private InstantsContinuation() {
    }

    /** Builds one continuation for the interface [descriptor] names, or null. */
    @Nullable
    public static Object create(@Nullable String descriptor) {
        if (descriptor == null || !descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            return null;
        }
        try {
            String binary = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            Class<?> type = Class.forName(binary);
            Class<?> contextType = contextType(type);
            InvocationHandler context = (proxy, method, args) -> {
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("toString")) return "HushGram empty coroutine context";
                Class<?> returns = method.getReturnType();
                if (returns == boolean.class) return false;
                if (returns == int.class) return 0;
                if (returns == long.class) return 0L;
                // The context folds over its elements with the caller's initial value; holding
                // none, it answers that initial value, the first argument of the fold call.
                if (args != null && args.length >= 2 && returns == Object.class) {
                    return args[0];
                }
                return null;
            };
            Object emptyContext = contextType == null
                ? null
                : Proxy.newProxyInstance(contextType.getClassLoader(), new Class<?>[]{contextType}, context);
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("toString")) return "HushGram gallery continuation";
                if (method.getName().equals("getContext")) return emptyContext;
                if (method.getName().equals("resumeWith") && args != null && args.length == 1) {
                    Object result = args[0];
                    Logger.diagnosticError(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                        SOURCE, () -> "the capture's last resume: " + result, failureOf(result));
                }
                return null;
            };
            Object continuation = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "continuation built over " + binary);
            return continuation;
        } catch (Throwable t) {
            Logger.printException(() -> "Could not build the capture continuation", t);
            return null;
        }
    }

    /** The type the continuation's context call answers, read off the interface itself. */
    @Nullable
    private static Class<?> contextType(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("getContext") && method.getParameterCount() == 0) {
                return method.getReturnType();
            }
        }
        return null;
    }

    /** The failure a Kotlin continuation's last resume carries, if [result] is one. */
    @Nullable
    private static Throwable failureOf(Object result) {
        try {
            Class<?> failureType = result.getClass();
            if (!failureType.getName().endsWith("Failure")) {
                return null;
            }
            java.lang.reflect.Field exception = failureType.getDeclaredField("exception");
            exception.setAccessible(true);
            Object value = exception.get(result);
            return value instanceof Throwable ? (Throwable) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
