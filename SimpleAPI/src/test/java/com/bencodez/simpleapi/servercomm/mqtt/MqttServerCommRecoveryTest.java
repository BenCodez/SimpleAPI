package com.bencodez.simpleapi.servercomm.mqtt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loopback broker restart regression. Run with -Dsimpleapi.mqtt.broker=/path/to/mosquitto. */
class MqttServerCommRecoveryTest {
	@TempDir Path directory;

	@Test
	void brokerRestartRestoresBidirectionalSubscriptions() throws Exception {
		String executable = System.getProperty("simpleapi.mqtt.broker");
		Assumptions.assumeTrue(executable != null && !executable.isBlank(), "ephemeral mosquitto not configured");
		int port = freePort();
		Path config = directory.resolve("mosquitto.conf");
		Files.writeString(config, "listener " + port + " 127.0.0.1\nallow_anonymous true\n");
		Process broker = startBroker(executable, config);
		awaitPort(port, Duration.ofSeconds(5));
		try (MqttServerComm first = new MqttServerComm("recovery-first", "tcp://127.0.0.1:" + port, null, null);
				MqttServerComm second = new MqttServerComm("recovery-second", "tcp://127.0.0.1:" + port, null, null)) {
			CountDownLatch firstReceived = new CountDownLatch(1);
			CountDownLatch secondReceived = new CountDownLatch(1);
			CountDownLatch firstAfter = new CountDownLatch(1);
			CountDownLatch secondAfter = new CountDownLatch(1);
			first.subscribe("recovery/second", 1, (topic, message) -> {
				if ("after".equals(new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8))) firstAfter.countDown();
				else firstReceived.countDown();
			});
			second.subscribe("recovery/first", 1, (topic, message) -> {
				if ("after".equals(new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8))) secondAfter.countDown();
				else secondReceived.countDown();
			});
			first.publish("recovery/second", "before", 1, false);
			second.publish("recovery/first", "before", 1, false);
			assertTrue(firstReceived.await(5, TimeUnit.SECONDS));
			assertTrue(secondReceived.await(5, TimeUnit.SECONDS));
			broker.destroy();
			assertTrue(broker.waitFor(5, TimeUnit.SECONDS));
			awaitDisconnected(first, Duration.ofSeconds(5));
			awaitDisconnected(second, Duration.ofSeconds(5));
			broker = startBroker(executable, config);
			awaitPort(port, Duration.ofSeconds(5));
			awaitConnected(first, Duration.ofSeconds(10));
			awaitConnected(second, Duration.ofSeconds(10));
			first.publish("recovery/second", "after", 1, true);
			second.publish("recovery/first", "after", 1, true);
			assertTrue(firstAfter.await(10, TimeUnit.SECONDS));
			assertTrue(secondAfter.await(10, TimeUnit.SECONDS));
		} finally {
			broker.destroyForcibly();
		}
	}

	private Process startBroker(String executable, Path config) throws IOException {
		Process process = new ProcessBuilder(executable, "-c", config.toString()).redirectErrorStream(true).start();
		return process;
	}

	private static void awaitDisconnected(MqttServerComm client, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (client.isConnected() && System.nanoTime() < deadline) Thread.sleep(25);
		assertTrue(!client.isConnected(), "MQTT disconnect was not observed");
	}

	private static void awaitConnected(MqttServerComm client, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!client.isConnected() && System.nanoTime() < deadline) Thread.sleep(25);
		assertTrue(client.isConnected(), "MQTT client did not reconnect");
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
	}

	private static void awaitPort(int port, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) { return; }
			catch (IOException ignored) { Thread.sleep(25); }
		}
		throw new IOException("broker did not open loopback port " + port);
	}
}
