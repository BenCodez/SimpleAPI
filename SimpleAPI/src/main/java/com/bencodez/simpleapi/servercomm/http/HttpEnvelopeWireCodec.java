package com.bencodez.simpleapi.servercomm.http;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

/**
 * Transforms semantic HTTP transport envelopes at the wire boundary.
 *
 * <p>The HTTP delivery queues always retain the untransformed semantic envelope. Implementations
 * may therefore change keys or policy across a restart without making already accepted deliveries
 * unreadable. A codec must be non-blocking and keep a stable policy for the lifetime of one
 * transport instance, produce envelopes within the HTTP transport size limit, and reject invalid
 * wire envelopes by throwing an unchecked exception.</p>
 */
public interface HttpEnvelopeWireCodec {
	JsonEnvelope encode(JsonEnvelope envelope);

	JsonEnvelope decode(JsonEnvelope envelope);

	static HttpEnvelopeWireCodec identity() {
		return Identity.INSTANCE;
	}

	final class Identity implements HttpEnvelopeWireCodec {
		private static final Identity INSTANCE = new Identity();

		private Identity() { }

		@Override public JsonEnvelope encode(JsonEnvelope envelope) { return envelope; }

		@Override public JsonEnvelope decode(JsonEnvelope envelope) { return envelope; }
	}
}
