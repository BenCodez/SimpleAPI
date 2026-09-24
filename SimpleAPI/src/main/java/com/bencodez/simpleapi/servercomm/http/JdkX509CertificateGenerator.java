package com.bencodez.simpleapi.servercomm.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.security.auth.x500.X500Principal;

/** Creates the private HTTP transport PKI using only standard JCA primitives. */
final class JdkX509CertificateGenerator {
	private static final byte[] ECDSA_SHA256 = sequence(oid("1.2.840.10045.4.3.2"));
	private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
			.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
			.withZone(ZoneOffset.UTC);

	private JdkX509CertificateGenerator() { }

	static X509Certificate create(String subject, KeyPair subjectKey, X509Certificate issuer, PrivateKey issuerKey,
			boolean certificateAuthority, boolean server, String subjectAlternativeName, Instant now) throws Exception {
		byte[] issuerName = issuer == null ? new X500Principal(subject).getEncoded()
				: issuer.getSubjectX500Principal().getEncoded();
		List<byte[]> extensions = new ArrayList<>();
		extensions.add(extension("2.5.29.19", true,
				certificateAuthority ? sequence(bool(true)) : sequence()));
		extensions.add(extension("2.5.29.15", true,
				certificateAuthority ? bitString(1, new byte[] { 0x06 }) : bitString(7, new byte[] { (byte) 0x80 })));
		if (!certificateAuthority) extensions.add(extension("2.5.29.37", false,
				sequence(oid(server ? "1.3.6.1.5.5.7.3.1" : "1.3.6.1.5.5.7.3.2"))));
		if (subjectAlternativeName != null) {
			byte tag;
			byte[] value;
			byte[] ipAddress = server ? parseIpLiteral(subjectAlternativeName) : null;
			if (ipAddress != null) {
				tag = (byte) 0x87;
				value = ipAddress;
			} else {
				if (server && (subjectAlternativeName.indexOf(':') >= 0
						|| subjectAlternativeName.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")))
					throw new IllegalArgumentException("Advertised HTTPS host is an invalid IP address");
				if (!StandardCharsets.US_ASCII.newEncoder().canEncode(subjectAlternativeName))
					throw new IllegalArgumentException("Certificate alternative name must be ASCII");
				tag = server ? (byte) 0x82 : (byte) 0x86;
				value = subjectAlternativeName.getBytes(StandardCharsets.US_ASCII);
			}
			extensions.add(extension("2.5.29.17", false, sequence(tagged(tag, value))));
		}
		byte[] tbs = sequence(
				tagged((byte) 0xa0, integer(BigInteger.valueOf(2))),
				integer(new BigInteger(160, new java.security.SecureRandom()).setBit(159)), ECDSA_SHA256, issuerName,
				sequence(time(now.minusSeconds(300)),
						time(now.plusSeconds(certificateAuthority ? 315360000L : 31536000L))),
				new X500Principal(subject).getEncoded(), subjectKey.getPublic().getEncoded(),
				tagged((byte) 0xa3, sequence(extensions.toArray(byte[][]::new))));
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(issuerKey == null ? subjectKey.getPrivate() : issuerKey);
		signer.update(tbs);
		byte[] encoded = sequence(tbs, ECDSA_SHA256, bitString(0, signer.sign()));
		try (ByteArrayInputStream input = new ByteArrayInputStream(encoded)) {
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
		}
	}

	static boolean isIpLiteral(String value) {
		return parseIpLiteral(value) != null;
	}

	private static byte[] parseIpLiteral(String value) {
		if (value == null || value.isEmpty()) return null;
		if (value.indexOf(':') >= 0) {
			if (!value.matches("[0-9A-Fa-f:.]+")) return null;
			try {
				byte[] parsed = InetAddress.getByName(value).getAddress();
				if (parsed.length == 16) return parsed;
				if (parsed.length == 4) {
					byte[] mapped = new byte[16];
					mapped[10] = (byte) 0xff;
					mapped[11] = (byte) 0xff;
					System.arraycopy(parsed, 0, mapped, 12, parsed.length);
					return mapped;
				}
				return null;
			} catch (Exception invalid) { return null; }
		}
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) return null;
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return null;
			try { if (Integer.parseInt(part) > 255) return null; }
			catch (NumberFormatException invalid) { return null; }
		}
		try { return InetAddress.getByName(value).getAddress(); }
		catch (Exception invalid) { return null; }
	}

	private static byte[] extension(String id, boolean critical, byte[] value) {
		return critical ? sequence(oid(id), bool(true), octetString(value)) : sequence(oid(id), octetString(value));
	}
	private static byte[] time(Instant value) {
		int year = value.atZone(ZoneOffset.UTC).getYear();
		String encoded = year >= 1950 && year <= 2049 ? UTC_TIME.format(value) : GENERALIZED_TIME.format(value);
		return tagged(year >= 1950 && year <= 2049 ? (byte) 0x17 : (byte) 0x18,
				encoded.getBytes(StandardCharsets.US_ASCII));
	}
	private static byte[] oid(String value) {
		String[] components = value.split("\\.");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeBase128(body, Long.parseLong(components[0]) * 40L + Long.parseLong(components[1]));
		for (int index = 2; index < components.length; index++) writeBase128(body, Long.parseLong(components[index]));
		return tagged((byte) 0x06, body.toByteArray());
	}
	private static void writeBase128(ByteArrayOutputStream output, long value) {
		byte[] encoded = new byte[10];
		int position = encoded.length;
		encoded[--position] = (byte) (value & 0x7f);
		while ((value >>>= 7) != 0) encoded[--position] = (byte) ((value & 0x7f) | 0x80);
		output.writeBytes(Arrays.copyOfRange(encoded, position, encoded.length));
	}
	private static byte[] integer(BigInteger value) { return tagged((byte) 0x02, value.toByteArray()); }
	private static byte[] bool(boolean value) { return tagged((byte) 0x01, new byte[] { value ? (byte) 0xff : 0 }); }
	private static byte[] octetString(byte[] value) { return tagged((byte) 0x04, value); }
	private static byte[] bitString(int unusedBits, byte[] value) {
		byte[] body = new byte[value.length + 1];
		body[0] = (byte) unusedBits;
		System.arraycopy(value, 0, body, 1, value.length);
		return tagged((byte) 0x03, body);
	}
	private static byte[] sequence(byte[]... values) { return tagged((byte) 0x30, concatenate(values)); }
	private static byte[] tagged(byte tag, byte[] value) {
		ByteArrayOutputStream output = new ByteArrayOutputStream(value.length + 6);
		output.write(tag);
		writeLength(output, value.length);
		output.writeBytes(value);
		return output.toByteArray();
	}
	private static void writeLength(ByteArrayOutputStream output, int length) {
		if (length < 128) { output.write(length); return; }
		int bytes = 0;
		for (int remaining = length; remaining != 0; remaining >>>= 8) bytes++;
		output.write(0x80 | bytes);
		for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) output.write(length >>> shift);
	}
	private static byte[] concatenate(byte[]... values) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		for (byte[] value : values) output.writeBytes(value);
		return output.toByteArray();
	}
}
