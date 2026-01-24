package com.bencodez.simpleapi.servercomm.redis;

import java.util.function.BiConsumer;

import lombok.Getter;
import redis.clients.jedis.JedisPubSub;

public class RedisListener extends JedisPubSub {
	private final RedisHandler redisHandler;

	@Getter
	private final String channel;

	private final BiConsumer<String, String> onPayload;

	public RedisListener(RedisHandler redisHandler, String channel, BiConsumer<String, String> onPayload) {
		this.redisHandler = redisHandler;
		this.channel = channel;
		this.onPayload = onPayload;
	}

	@Override
	public void onMessage(String channel, String message) {
		redisHandler.debug("Redis Message: " + channel + "," + message);
		if (channel.equals(this.channel)) {
			onPayload.accept(channel, message); // full JSON payload
		}
	}
}
