package com.bencodez.simpleapi.tests.servercomm.http;

import static com.bencodez.simpleapi.tests.support.TestReflection.*;

import com.bencodez.simpleapi.tests.support.TestReflection;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.util.Collection;
import java.util.List;

/** Reflective views of the real package-private protocol and its record values. */
final class HttpProtocolAccess {
    private static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpTransportProtocol");
    static final int MAX_BODY_BYTES = (Integer) field(TYPE, null, "MAX_BODY_BYTES");
    static final int MAX_ENVELOPE_BYTES = (Integer) field(TYPE, null, "MAX_ENVELOPE_BYTES");
    static final int MAX_QUEUE = (Integer) field(TYPE, null, "MAX_QUEUE");
    static final long MAX_CLOCK_SKEW_MILLIS = (Long) field(TYPE, null, "MAX_CLOCK_SKEW_MILLIS");
    static byte[] request(String server, String session, long sequence, Collection<String> acks, Collection<String> confirmations, Collection<Delivery> messages) { return call(TYPE, "request", server, session, sequence, acks, confirmations, messages); }
    static List<Delivery> fittingMessages(String server, String session, long sequence, Collection<String> acks, Collection<String> confirmations, Collection<Delivery> messages) { return deliveries(call(TYPE, "fittingMessages", server, session, sequence, acks, confirmations, messages)); }
    static byte[] storedDelivery(Delivery delivery) { return call(TYPE, "storedDelivery", delivery); }
    static Delivery parseStoredDelivery(byte[] body) { return new Delivery(call(TYPE, "parseStoredDelivery", body)); }
    static Packet parsePacket(byte[] body) { return new Packet(call(TYPE, "parsePacket", body)); }
    static Enrollment parseEnrollment(byte[] body) { return new Enrollment(call(TYPE, "parseEnrollment", body)); }
    static List<Delivery> deliveries(Collection<?> values) { return values.stream().map(Delivery::new).toList(); }
    static class View implements TestReflection.Handle {
        private final Object target;
        View(Object target) { this.target = java.util.Objects.requireNonNull(target, "Missing production value"); }
        @Override public Object target() { return target; }
        @Override public boolean equals(Object other) { return target.equals(unwrap(other)); }
        @Override public int hashCode() { return target.hashCode(); }
        @Override public String toString() { return target.toString(); }
    }
    static final class Delivery extends View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpTransportProtocol$Delivery");
        Delivery(Object target) { super(target); }
        Delivery(String id, JsonEnvelope envelope) { super(construct(TYPE, id, envelope)); }
        String id() { return call(this, "id"); }
        JsonEnvelope envelope() { return call(this, "envelope"); }
    }
    static final class Packet extends View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpTransportProtocol$Packet");
        Packet(Object target) { super(target); }
        String server() { return call(this, "server"); }
        String session() { return call(this, "session"); }
        long sequence() { return call(this, "sequence"); }
        List<String> acks() { return call(this, "acks"); }
        List<String> ackConfirmations() { return call(this, "ackConfirmations"); }
        List<Delivery> messages() { return deliveries(call(this, "messages")); }
    }
    static final class Enrollment extends View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpTransportProtocol$Enrollment");
        Enrollment(Object target) { super(target); }
        String server() { return call(this, "server"); }
        String token() { return call(this, "token"); }
    }
}
