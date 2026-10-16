package com.bencodez.simpleapi.tests.servercomm.http;

import static com.bencodez.simpleapi.tests.support.TestReflection.*;

import com.bencodez.simpleapi.servercomm.http.HttpBackendTransportConnector;
import com.bencodez.simpleapi.servercomm.http.HttpClientCredentialStore;
import com.bencodez.simpleapi.servercomm.http.HttpConnectionCode;
import com.bencodez.simpleapi.servercomm.http.HttpEnrollmentAuthority;
import com.bencodez.simpleapi.servercomm.http.HttpEnvelopeWireCodec;
import com.bencodez.simpleapi.servercomm.http.HttpProxyTransportServer;
import com.bencodez.simpleapi.servercomm.http.HttpTlsIdentity;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.net.ssl.TrustManager;

/** Narrow test-only entry points for the original HTTP implementation internals. */
final class HttpTestAccess {
    @SuppressWarnings("unchecked")
    static final Class<? extends IllegalStateException> RENEWAL_RATE_LIMIT = (Class<? extends IllegalStateException>) type("com.bencodez.simpleapi.servercomm.http.HttpEnrollmentAuthority$RenewalRateLimitException");
    static final Duration LONG_POLL = (Duration) field(HttpProxyTransportServer.class, null, "LONG_POLL");
    static final int CALLBACK_QUEUE_CAPACITY = (Integer) field(HttpBackendTransportConnector.class, null, "CALLBACK_QUEUE_CAPACITY");
    static HttpProxyTransportServer server(InetSocketAddress bind, HttpTlsIdentity identity, HttpEnrollmentAuthority authority, Consumer<HttpProxyTransportServer.ReceivedEnvelope> callback) throws Exception { return construct(HttpProxyTransportServer.class, bind, identity, authority, callback); }
    static HttpProxyTransportServer server(InetSocketAddress bind, HttpTlsIdentity identity, HttpEnrollmentAuthority authority, Path directory, Consumer<HttpProxyTransportServer.ReceivedEnvelope> callback, HttpProxyTransportServer.DeliveryAcknowledgement acknowledged, LongSupplier clock) throws Exception { return construct(HttpProxyTransportServer.class, bind, identity, authority, directory, callback, acknowledged, clock); }
    static HttpBackendTransportConnector connector(HttpConnectionCode code, String server, HttpClientCredentialStore.ClientCredential credential, Consumer<JsonEnvelope> callback) throws Exception { return construct(HttpBackendTransportConnector.class, code, server, credential, callback); }
    static HttpBackendTransportConnector connector(HttpClientCredentialStore.HttpClientProfile profile, HttpClientCredentialStore.ClientCredential credential, Consumer<JsonEnvelope> callback) throws Exception { return construct(HttpBackendTransportConnector.class, profile, credential, callback); }
    static HttpEnrollmentAuthority authority(HttpTlsIdentity identity, Clock clock) throws Exception { return construct(HttpEnrollmentAuthority.class, identity, clock); }
    static HttpEnrollmentAuthority authority(HttpTlsIdentity identity, Clock clock, Path stateFile) throws Exception { return construct(HttpEnrollmentAuthority.class, identity, clock, stateFile); }
    static HttpTlsIdentity identity(Path directory, String host, Clock clock) throws Exception { return call(HttpTlsIdentity.class, "loadOrCreate", directory, host, clock); }
    static HttpTlsIdentity.IssuedClientCertificate issueClientCertificate(HttpTlsIdentity identity, String server, Instant issuedAt) throws Exception { return call(identity, "issueClientCertificate", server, issuedAt); }
    static int fixedBodyErrorStatus(Headers headers, int maximum) { return call(HttpProxyTransportServer.class, "fixedBodyErrorStatus", headers, maximum); }
    static boolean shutdown(ExecutorService executor) { return call(HttpProxyTransportServer.class, "shutdown", executor); }
    static byte[] readLimited(InputStream body) throws IOException { return call(HttpBackendTransportConnector.class, "readLimited", body); }
    static Duration renewalRetryDelay(Duration remaining) { return call(HttpBackendTransportConnector.class, "renewalRetryDelay", remaining); }
    static long renewalDeadline(long now, Duration delay) { return call(HttpBackendTransportConnector.class, "renewalDeadline", now, delay); }
    static long timeoutNanos(Duration timeout) { return call(HttpBackendTransportConnector.class, "timeoutNanos", timeout); }
    static LimitedResponse sendLimited(HttpClient client, HttpRequest request) throws IOException, InterruptedException { return new LimitedResponse(call(HttpBackendTransportConnector.class, "sendLimited", client, request)); }
    static boolean needsRenewal(X509Certificate certificate, Clock clock) { return call(HttpTlsIdentity.class, "needsRenewal", certificate, clock); }
    static boolean needsCaRenewal(X509Certificate certificate, Clock clock) { return call(HttpTlsIdentity.class, "needsCaRenewal", certificate, clock); }
    static TrustManager[] trustManagers(X509Certificate certificate) throws Exception { return call(HttpTlsIdentity.class, "trustManagers", certificate); }
    static boolean isIpLiteral(String value) { return call(type("com.bencodez.simpleapi.servercomm.http.JdkX509CertificateGenerator"), "isIpLiteral", value); }
    static String encodeLegacy(HttpConnectionCode code) { return call(code, "encodeLegacy"); }
    static StagedCredential stageReplacement(Path directory, HttpTlsIdentity.IssuedClientCertificate issued) throws Exception { return new StagedCredential(call(HttpClientCredentialStore.class, "stageReplacement", directory, issued)); }
    static void activateReplacement(Path directory, StagedCredential staged) throws IOException { call(HttpClientCredentialStore.class, "activateReplacement", directory, staged); }
    static BackendState backendStateForTest(HttpProxyTransportServer server, String id) throws IOException { return new BackendState((Object) call(server, "backendStateForTest", id)); }
    static int backendCountForTest(HttpProxyTransportServer server) { return call(server, "backendCountForTest"); }
    static boolean pollerAlive(HttpBackendTransportConnector connector) { return call(connector, "pollerAlive"); }
    static List<HttpProtocolAccess.Delivery> accept(HttpBackendTransportConnector connector, List<HttpProtocolAccess.Delivery> deliveries) { return HttpProtocolAccess.deliveries(call(connector, "accept", deliveries)); }
    static void dispatch(HttpBackendTransportConnector connector, HttpProtocolAccess.Delivery delivery) { call(connector, "dispatch", delivery); }
    static void completeIncoming(HttpBackendTransportConnector connector, String id, boolean success) { call(connector, "completeIncoming", id, success); }
    static int queuedOutgoing(HttpBackendTransportConnector connector) { return call(connector, "queuedOutgoing"); }
    static List<String> drainAcknowledgements(HttpBackendTransportConnector connector) { return call(connector, "drainAcknowledgements"); }
    @FunctionalInterface interface EnrollmentOperation<T> { T run() throws Exception; }
    static <T> T withEnrollmentLock(Path directory, EnrollmentOperation<T> operation) throws Exception {
        Class<?> operationType = type("com.bencodez.simpleapi.servercomm.http.HttpClientCredentialStore$EnrollmentOperation");
        Object callback = java.lang.reflect.Proxy.newProxyInstance(operationType.getClassLoader(), new Class<?>[] { operationType },
                (proxy, method, arguments) -> operation.run());
        return call(HttpClientCredentialStore.class, "withEnrollmentLock", directory, callback);
    }
    static final class StagedCredential extends HttpProtocolAccess.View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpClientCredentialStore$StagedCredential");
        StagedCredential(Object target) { super(target); }
        String name() { return call(this, "name"); }
        HttpClientCredentialStore.ClientCredential credential() { return call(this, "credential"); }
        HttpClientCredentialStore.HttpClientProfile profile() { return call(this, "profile"); }
    }
    static final class LimitedResponse extends HttpProtocolAccess.View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpBackendTransportConnector$LimitedResponse");
        LimitedResponse(Object target) { super(target); }
        int statusCode() { return call(this, "statusCode"); }
        byte[] body() { return call(this, "body"); }
    }
    static final class Response extends HttpProtocolAccess.View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpProxyTransportServer$Response");
        Response(Object target) { super(target); }
        Collection<String> acks() { return call(this, "acks"); }
        Collection<HttpProtocolAccess.Delivery> messages() { return HttpProtocolAccess.deliveries(call(this, "messages")); }
    }
    static final class BackendState extends HttpProtocolAccess.View {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpProxyTransportServer$BackendState");
        BackendState(Object target) { super(target); }
        BackendState() { super(construct(TYPE)); }
        BackendState(LongSupplier clock) { super(construct(TYPE, clock)); }
        BackendState(String id, DurableOutgoingQueue queue, HttpProxyTransportServer.DeliveryAcknowledgement acknowledged) { super(construct(TYPE, id, queue, acknowledged)); }
        BackendState(String id, DurableOutgoingQueue queue, HttpInboundStoreAccess inbound, HttpProxyTransportServer.DeliveryAcknowledgement acknowledged) { super(construct(TYPE, id, queue, inbound, acknowledged)); }
        boolean beginPollForTest() { return call(this, "beginPollForTest"); }
        void endPollForTest() { call(this, "endPollForTest"); }
        boolean acceptSession(String session, long sequence) { return call(this, "acceptSession", session, sequence); }
        List<HttpProtocolAccess.Delivery> acceptIncoming(List<HttpProtocolAccess.Delivery> deliveries) { return HttpProtocolAccess.deliveries(call(this, "acceptIncoming", deliveries)); }
        boolean enqueue(HttpProtocolAccess.Delivery delivery) { return call(this, "enqueue", delivery); }
        void acknowledge(Collection<String> ids) throws IOException { call(this, "acknowledge", ids); }
        void beginIncoming(String id) throws IOException { call(this, "beginIncoming", id); }
        void completeIncomingDurably(String id) throws IOException { call(this, "completeIncomingDurably", id); }
        void completeIncoming(String id, boolean success) { call(this, "completeIncoming", id, success); }
        void confirmIncoming(Collection<String> ids) throws IOException { call(this, "confirmIncoming", ids); }
        Response await(String server, String session, long sequence) { return new Response(call(this, "await", server, session, sequence)); }
        Response await(String server, String session, long sequence, Collection<String> confirmations) { return new Response(call(this, "await", server, session, sequence, confirmations)); }
        Response await(String server, String session, long sequence, Collection<String> confirmations, HttpEnvelopeWireCodec codec) { return new Response(call(this, "await", server, session, sequence, confirmations, codec)); }
    }
    @FunctionalInterface interface DirectoryForcer { void force(Path directory) throws IOException; }
    static final class DurableOutgoingQueue extends HttpProtocolAccess.View implements AutoCloseable {
        static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpProxyTransportServer$DurableOutgoingQueue");
        DurableOutgoingQueue(Path directory, DirectoryForcer forcer) throws IOException { super(create(directory, forcer)); }
        private static Object create(Path directory, DirectoryForcer forcer) {
            Class<?> callbackType = type("com.bencodez.simpleapi.servercomm.http.HttpProxyTransportServer$DurableOutgoingQueue$DirectoryForcer");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[] { callbackType },
                    (proxy, method, arguments) -> { forcer.force((Path) arguments[0]); return null; });
            return construct(TYPE, directory, callback);
        }
        boolean hasPendingDeliveries() { return call(this, "hasPendingDeliveries"); }
        Map<String, List<HttpProtocolAccess.Delivery>> load() throws IOException {
            Map<String, ? extends Collection<?>> loaded = call(this, "load");
            Map<String, List<HttpProtocolAccess.Delivery>> views = new LinkedHashMap<>();
            loaded.forEach((server, deliveries) -> views.put(server, HttpProtocolAccess.deliveries(deliveries)));
            return views;
        }
        @Override public void close() { call(this, "close"); }
    }
}
