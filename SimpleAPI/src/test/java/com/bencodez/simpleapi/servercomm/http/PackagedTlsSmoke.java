package com.bencodez.simpleapi.servercomm.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** Invoked in a fresh JVM with only the shaded JAR and this fixture on its classpath. */
public final class PackagedTlsSmoke {
    private PackagedTlsSmoke() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Expected state directory and full artifact");
        Path state = Path.of(args[0]);
        Path full = Path.of(args[1]);
        requireFromJar(HttpTlsIdentity.class, full);
        HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(state.resolve("trusted"), "127.0.0.1");
        require(Security.getProvider("BC") != null, "BC provider was not registered");
        requireFromJar(Security.getProvider("BC").getClass(), full);
        X509Certificate ca = identity.caCertificate();
        X509Certificate server = identity.serverCertificate();
        ca.verify(ca.getPublicKey());
        server.verify(ca.getPublicKey());
        server.checkValidity();
        require(ca.getBasicConstraints() >= 0 && server.getBasicConstraints() == -1, "Incorrect CA constraints");
        require(server.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "Missing server-auth usage");

        var client = identity.issueClientCertificate("backend-one");
        client.certificate().verify(ca.getPublicKey());
        require(identity.validClientCertificate("backend-one", client.certificate()), "Valid client rejected");
        require(!identity.validClientCertificate("backend-two", client.certificate()), "Wrong backend identity accepted");
        require(!identity.validClientCertificate("backend-one", server), "Server certificate accepted as a client");
        SSLContext serverContext = identity.serverContext();
        exchange(serverContext, clientContext(client, ca), true);
        exchange(serverContext, clientContext(null, ca), false);
        HttpTlsIdentity foreign = HttpTlsIdentity.loadOrCreate(state.resolve("foreign"), "127.0.0.1");
        var foreignClient = foreign.issueClientCertificate("backend-one");
        require(!identity.validClientCertificate("backend-one", foreignClient.certificate()), "Foreign CA accepted");
        exchange(serverContext, clientContext(foreignClient, ca), false);

        HttpTlsIdentity reloaded = HttpTlsIdentity.loadOrCreate(state.resolve("trusted"), "127.0.0.1");
        require(Arrays.equals(server.getEncoded(), reloaded.serverCertificate().getEncoded()), "Reload replaced the identity");
        HttpTlsIdentity renewed = HttpTlsIdentity.loadOrCreate(state.resolve("trusted"), "127.0.0.1",
                Clock.offset(Clock.systemUTC(), Duration.ofDays(340)));
        renewed.serverCertificate().verify(ca.getPublicKey());
        require(renewed.serverCertificate().getNotAfter().after(server.getNotAfter()), "Server renewal failed");
        HttpTlsIdentity renewedCa = HttpTlsIdentity.loadOrCreate(state.resolve("trusted"), "127.0.0.1",
                Clock.offset(Clock.systemUTC(), Duration.ofDays(3400)));
        renewedCa.caCertificate().verify(ca.getPublicKey());
        renewedCa.serverCertificate().verify(renewedCa.caCertificate().getPublicKey());
        require(renewedCa.caCertificate().getNotAfter().after(ca.getNotAfter()), "CA renewal failed");
        System.out.println("Packaged TLS smoke passed: certificate issuance, PKCS12, mutual TLS, rejection, reload and renewal");
    }

    private static SSLContext clientContext(HttpTlsIdentity.IssuedClientCertificate client, X509Certificate ca)
            throws Exception {
        KeyManager[] keys = new KeyManager[0];
        if (client != null) {
            byte[] encoded = client.pkcs12();
            char[] password = client.password();
            try {
                KeyStore store = KeyStore.getInstance("PKCS12");
                try (var input = new ByteArrayInputStream(encoded)) { store.load(input, password); }
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, password);
                keys = factory.getKeyManagers();
            } finally {
                Arrays.fill(encoded, (byte) 0);
                Arrays.fill(password, '\0');
            }
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, new char[0]);
        trust.setCertificateEntry("ca", ca);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys, factory.getTrustManagers(), null);
        return context;
    }

    private static void exchange(SSLContext serverContext, SSLContext clientContext, boolean expected) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (SSLServerSocket listener = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            listener.setSoTimeout(5000);
            listener.setNeedClientAuth(true);
            var accepted = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    require(socket.getInputStream().read() == 41, "Missing authenticated request");
                    socket.getOutputStream().write(42);
                    return true;
                } catch (SSLException rejected) {
                    return false;
                }
            });
            boolean clientAccepted = false;
            try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 5000);
                socket.setSoTimeout(5000);
                var parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                socket.getOutputStream().write(41);
                clientAccepted = socket.getInputStream().read() == 42;
            } catch (IOException rejected) {
                if (expected) throw rejected;
            }
            // A negative case must be an actual TLS rejection on the server, not a connect/timeout error.
            require(accepted.get(10, TimeUnit.SECONDS) == expected, "Unexpected server handshake result");
            require(clientAccepted == expected, "Unexpected client handshake result");
        } finally {
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "TLS fixture worker did not stop");
        }
    }

    private static void requireFromJar(Class<?> type, Path full) throws Exception {
        Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isSameFile(source, full), "Loaded outside packaged artifact: " + type.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
