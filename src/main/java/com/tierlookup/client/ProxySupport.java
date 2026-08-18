package com.tierlookup.client;

import java.lang.reflect.Method;

/**
* Handles java.lang.Object methods for JDK dynamic proxies.
*
* Returning null from InvocationHandler for hashCode() is fatal because the JDK proxy
* must unbox the result to int. Brigadier hashes Command instances while merging the
* client command tree, so every proxy used by TierLookup must have real Object semantics.
*/ public final class ProxySupport {
    private ProxySupport() {
    }
    public static boolean isObjectMethod(Method method) {
        if (method == null) return false;
        String n = method.getName();
        return (n.equals("hashCode") && method.getParameterCount() == 0) || (n.equals("equals") && method.getParameterCount() == 1) || (n.equals("toString") && method.getParameterCount() == 0);
    }
    public static Object invokeObjectMethod(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            case "toString" -> label + "@" + Integer.toHexString(System.identityHashCode(proxy));
            default -> throw new IllegalArgumentException("Unsupported Object method: " + method);
        };
    }
}
