package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.BasicConfigurationNode;

import com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView;
import com.bencodez.simpleapi.file.velocity.VelocityYMLFile;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigBungee;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigSpigot;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigVelocity;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigView;

import net.md_5.bungee.config.Configuration;

class PostgreSqlTlsConfigTest {
	@TempDir Path tempDir;

	@Test void allAdaptersReadExplicitModeAndPreserveLegacyDefault() {
		MemoryConfiguration spigot = new MemoryConfiguration();
		Configuration bungee = new Configuration();
		var shared = BasicConfigurationNode.root();
		VelocityYMLFile velocity = new VelocityYMLFile(tempDir.resolve("mysql.yml").toFile());
		assertEquals(PostgreSqlTlsMode.LEGACY, new MysqlConfigSpigot(spigot).getPostgreSqlTlsMode());
		assertEquals(PostgreSqlTlsMode.LEGACY, new MysqlConfigBungee(bungee).getPostgreSqlTlsMode());
		assertEquals(PostgreSqlTlsMode.LEGACY,
				new MysqlConfigView(new ConfigurateConfigView(shared)).getPostgreSqlTlsMode());
		assertEquals(PostgreSqlTlsMode.LEGACY, new MysqlConfigVelocity(velocity).getPostgreSqlTlsMode());

		spigot.set("PostgreSqlTlsMode", "verify-full");
		bungee.set("PostgreSqlTlsMode", "verify-full");
		shared.node("PostgreSqlTlsMode").raw("verify-full");
		velocity.getNode("MySQL", "PostgreSqlTlsMode").raw("verify-full");
		assertEquals(PostgreSqlTlsMode.VERIFY_FULL, new MysqlConfigSpigot(spigot).getPostgreSqlTlsMode());
		assertEquals(PostgreSqlTlsMode.VERIFY_FULL, new MysqlConfigBungee(bungee).getPostgreSqlTlsMode());
		assertEquals(PostgreSqlTlsMode.VERIFY_FULL,
				new MysqlConfigView(new ConfigurateConfigView(shared)).getPostgreSqlTlsMode());
		assertEquals(PostgreSqlTlsMode.VERIFY_FULL,
				new MysqlConfigVelocity("MySQL", velocity).getPostgreSqlTlsMode());
	}

	@Test void invalidModeFailsAtConfigLoad() {
		MemoryConfiguration spigot = new MemoryConfiguration();
		spigot.set("PostgreSqlTlsMode", "anything");
		assertThrows(IllegalArgumentException.class, () -> new MysqlConfigSpigot(spigot));
	}
}
