package com.bencodez.simpleapi.tests.servercomm.redis;

import com.bencodez.simpleapi.servercomm.redis.RedisHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedisHandlerClientConfigTest {

	@Test
	void tlsCanBeEnabled() {
		redis.clients.jedis.JedisClientConfig config = com.bencodez.simpleapi.tests.support.TestReflection.call(
                RedisHandler.class, "buildClientConfig", "", "", 0, true);

		assertTrue(config.isSsl());
		assertEquals("HTTPS", config.getSslParameters().getEndpointIdentificationAlgorithm());
	}

	@Test
	void tlsRemainsDisabledByDefault() {
		assertFalse(((redis.clients.jedis.JedisClientConfig) com.bencodez.simpleapi.tests.support.TestReflection.call(
                RedisHandler.class, "buildClientConfig", "", "", 0, false)).isSsl());
	}
}
