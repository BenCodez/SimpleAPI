package com.bencodez.simpleapi.updater;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.bukkit.plugin.java.JavaPlugin;

import lombok.Getter;

/**
 * Handles checking for plugin updates from Spigot.
 */
public class Updater {

	/**
	 * Possible update check results.
	 */
	public enum UpdateResult {

		/**
		 * Invalid resource id.
		 */
		BAD_RESOURCEID,

		/**
		 * Update checking is disabled.
		 */
		DISABLED,

		/**
		 * Failed because no version was returned.
		 */
		FAIL_NOVERSION,

		/**
		 * Failed to contact Spigot.
		 */
		FAIL_SPIGOT,

		/**
		 * No update is available.
		 */
		NO_UPDATE,

		/**
		 * An update is available.
		 */
		UPDATE_AVAILABLE
	}

	/**
	 * Shared HTTP client for update checks.
	 */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	/**
	 * Current installed plugin version.
	 */
	@Getter
	private String oldVersion;

	/**
	 * Plugin instance.
	 */
	@Getter
	private JavaPlugin plugin;

	/**
	 * Spigot resource id.
	 */
	@Getter
	private String resourceId = "";

	/**
	 * Result of the update check.
	 */
	@Getter
	private UpdateResult result = UpdateResult.DISABLED;

	/**
	 * Latest remote version.
	 */
	@Getter
	private String version;

	/**
	 * Creates a new updater.
	 *
	 * @param plugin     Plugin instance
	 * @param resourceId Spigot resource id
	 * @param disabled   Whether update checking is disabled
	 */
	public Updater(JavaPlugin plugin, Integer resourceId, boolean disabled) {
		this.resourceId = String.valueOf(resourceId);
		this.plugin = plugin;
		this.oldVersion = this.plugin.getDescription().getVersion();

		if (disabled) {
			this.result = UpdateResult.DISABLED;
			return;
		}

		run();
	}

	private void run() {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId))
					.timeout(Duration.ofSeconds(2)).GET().build();

			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				result = UpdateResult.FAIL_SPIGOT;
				return;
			}

			version = response.body();
			if (version != null) {
				version = version.trim();
			}

			if (version == null || version.isEmpty()) {
				result = UpdateResult.FAIL_NOVERSION;
				return;
			}

			versionCheck();
			return;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			result = UpdateResult.FAIL_SPIGOT;
			return;
		} catch (IOException e) {
			result = UpdateResult.FAIL_SPIGOT;
			return;
		} catch (Exception e) {
			result = UpdateResult.FAIL_SPIGOT;
		}
	}

	/**
	 * Checks whether the local version should be updated.
	 *
	 * @param localVersion  Local version
	 * @param remoteVersion Remote version
	 * @return {@code true} if update is needed
	 */
	public boolean shouldUpdate(String localVersion, String remoteVersion) {
		return !localVersion.equalsIgnoreCase(remoteVersion);
	}

	/**
	 * Compares local and remote versions and sets the result.
	 */
	private void versionCheck() {
		if (shouldUpdate(oldVersion, version)) {
			result = UpdateResult.UPDATE_AVAILABLE;
		} else {
			result = UpdateResult.NO_UPDATE;
		}
	}
}