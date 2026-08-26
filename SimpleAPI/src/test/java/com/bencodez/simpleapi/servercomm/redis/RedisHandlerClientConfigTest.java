package com.bencodez.simpleapi.servercomm.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedisHandlerClientConfigTest {

	@Test
	void tlsCanBeEnabled() {
		var config = RedisHandler.buildClientConfig("", "", 0, true);

		assertTrue(config.isSsl());
		assertEquals("HTTPS", config.getSslParameters().getEndpointIdentificationAlgorithm());
	}

	@Test
	void tlsRemainsDisabledByDefault() {
		assertFalse(RedisHandler.buildClientConfig("", "", 0, false).isSsl());
	}
}
