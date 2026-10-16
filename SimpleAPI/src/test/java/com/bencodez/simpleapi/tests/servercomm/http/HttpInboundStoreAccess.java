package com.bencodez.simpleapi.tests.servercomm.http;

import static com.bencodez.simpleapi.tests.support.TestReflection.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Test-only view of the original durable inbound store. */
final class HttpInboundStoreAccess extends HttpProtocolAccess.View {
    static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpInboundDeliveryStore");
    HttpInboundStoreAccess(Object target) { super(target); }
    HttpInboundStoreAccess(Path directory) throws IOException { super(construct(TYPE, directory)); }
    static final class State {
        private static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpInboundDeliveryStore$State");
        static final Object RESERVED = field(TYPE, null, "RESERVED");
        static final Object RUNNING = field(TYPE, null, "RUNNING");
        static final Object COMPLETED = field(TYPE, null, "COMPLETED");
    }
    static HttpInboundStoreAccess open(Path parent, String directory) throws IOException { return new HttpInboundStoreAccess((Object) call(TYPE, "open", parent, directory)); }
    static HttpInboundStoreAccess inspect(Path parent, String directory) throws IOException { return new HttpInboundStoreAccess((Object) call(TYPE, "inspect", parent, directory)); }
    static HttpInboundStoreAccess inspect(Path directory) throws IOException { return new HttpInboundStoreAccess((Object) call(TYPE, "inspect", directory)); }
    static Set<String> discover(Path parent) throws IOException { return call(TYPE, "discover", parent); }
    Object state(String id) { return call(this, "state", id); }
    void reserve(String id) throws IOException { call(this, "reserve", id); }
    void markRunning(String id) throws IOException { call(this, "markRunning", id); }
    void markCompleted(String id) throws IOException { call(this, "markCompleted", id); }
    void seal() { call(this, "seal"); }
    void sealAndDeleteIfEmpty() throws IOException { call(this, "sealAndDeleteIfEmpty"); }
}
