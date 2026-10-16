package com.bencodez.simpleapi.tests.servercomm.http;

import static com.bencodez.simpleapi.tests.support.TestReflection.*;

import java.security.cert.X509Certificate;

/** Calls production secret utilities rather than reproducing cryptographic logic in tests. */
final class HttpSecretsAccess {
    private static final Class<?> TYPE = type("com.bencodez.simpleapi.servercomm.http.HttpTransportSecrets");
    static String randomToken() { return call(TYPE, "randomToken"); }
    static String certificatePin(X509Certificate certificate) { return call(TYPE, "certificatePin", certificate); }
    static String sha256Hex(byte[] value) { return call(TYPE, "sha256Hex", value); }
}
