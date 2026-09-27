package com.bencodez.simpleapi.servercomm.http;

import java.util.Objects;

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

	/** Serializes a codec shared by concurrent HTTP sessions. */
	static HttpEnvelopeWireCodec serialized(HttpEnvelopeWireCodec codec) {
		Objects.requireNonNull(codec, "wireCodec");
		return codec == Identity.INSTANCE ? codec : new Serialized(codec);
	}

	final class Identity implements HttpEnvelopeWireCodec {
		private static final Identity INSTANCE = new Identity();

		private Identity() { }

		@Override public JsonEnvelope encode(JsonEnvelope envelope) { return envelope; }

		@Override public JsonEnvelope decode(JsonEnvelope envelope) { return envelope; }
	}

	final class Serialized implements HttpEnvelopeWireCodec {
		private final HttpEnvelopeWireCodec delegate;

		private Serialized(HttpEnvelopeWireCodec delegate) {
			this.delegate = delegate;
		}

		@Override
		public JsonEnvelope encode(JsonEnvelope envelope) {
			synchronized (delegate) {
				return delegate.encode(envelope);
			}
		}

		@Override
		public JsonEnvelope decode(JsonEnvelope envelope) {
			synchronized (delegate) {
				return delegate.decode(envelope);
			}
		}
	}
}
