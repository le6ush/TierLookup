package com.tierlookup.client;

import java.lang.reflect.Method;

/**
* Registers Fabric callbacks through the PUBLIC Event API type.
*
* Do not reflect on event.getClass(): Fabric's concrete ArrayBackedEvent implementation
* is package-private. Invoking its otherwise-public register method reflectively from a
* mod causes IllegalAccessException on Java 21. The generic Event.register(T) method
* erases to register(Object), so invoking that public API method is stable here.
*/ public final class EventBridge {
    private EventBridge() {
    }
    public static void register(Object event, Object listener) throws Exception {
        if (event == null) throw new NullPointerException("event");
        if (listener == null) throw new NullPointerException("listener");
        Class<?> eventApi = Class.forName("net.fabricmc.fabric.api.event.Event");
        if (!eventApi.isInstance(event)) {
            throw new IllegalArgumentException("Not a Fabric Event: " + event.getClass().getName());
        }
        Method register = eventApi.getMethod("register", Object.class);
        
        register.invoke(event, listener);
        
    }
}
