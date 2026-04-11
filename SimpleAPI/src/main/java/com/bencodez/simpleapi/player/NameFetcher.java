package com.bencodez.simpleapi.player;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import lombok.Getter;

public class NameFetcher implements Callable<Map<UUID, String>> {

	private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Getter
	private final List<UUID> uuids;

	public NameFetcher(List<UUID> uuids) {
		this.uuids = ImmutableList.copyOf(uuids);
	}

	@Override
	public Map<UUID, String> call() throws Exception {
		Map<UUID, String> uuidStringMap = new HashMap<>();

		for (UUID uuid : uuids) {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(PROFILE_URL + uuid.toString().replace("-", ""))).GET()
					.timeout(Duration.ofSeconds(5)).build();

			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 204 || response.statusCode() == 404) {
				continue;
			}

			if (response.statusCode() == 429) {
				System.out.println("VotingPlugin NameFetcher: Sent too many requests");
				continue;
			}

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Failed to fetch name for uuid " + uuid + ", HTTP " + response.statusCode());
			}

			JsonElement parsed = JsonParser.parseString(response.body());
			if (parsed == null || !parsed.isJsonObject()) {
				continue;
			}

			JsonObject responseObject = parsed.getAsJsonObject();

			String errorMessage = null;
			if (responseObject.has("errorMessage") && !responseObject.get("errorMessage").isJsonNull()) {
				errorMessage = responseObject.get("errorMessage").getAsString();
			}

			String cause = null;
			if (responseObject.has("cause") && !responseObject.get("cause").isJsonNull()) {
				cause = responseObject.get("cause").getAsString();
			}

			if (errorMessage != null && errorMessage.equals("TooManyRequestsException")) {
				System.out.println("VotingPlugin NameFetcher: Sent too many requests");
				continue;
			}

			if (cause != null && !cause.isEmpty()) {
				throw new IllegalStateException(errorMessage != null ? errorMessage : cause);
			}

			if (!responseObject.has("name") || responseObject.get("name").isJsonNull()) {
				continue;
			}

			String name = responseObject.get("name").getAsString();
			if (name == null || name.isEmpty()) {
				continue;
			}

			uuidStringMap.put(uuid, name);
		}

		return uuidStringMap;
	}
}