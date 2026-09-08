package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigView;
import com.bencodez.simpleapi.sql.mysql.queries.Query;
import com.bencodez.simpleapi.tests.shared.SharedRuntimeClasspath;

class SharedSqlTest {
    @Test void readsExistingDefaultsWithoutAPlatformOrOpeningConnections() throws Exception {
        MysqlConfigView config=new MysqlConfigView(new ConfigurateConfigView(BasicConfigurationNode.root()));
        assertEquals(1, config.getMaxThreads());
        assertEquals(-1, config.getLifeTime());
        assertEquals(2, config.getMinimumIdle());
        assertEquals(50_000, config.getConnectionTimeout());
        assertEquals(DbType.MYSQL, config.getDbType());
        assertFalse(config.isUseSSL());
        assertFalse(config.hasTableNameSet());
        SharedRuntimeClasspath.assertPlatformsAbsent();
    }
    @Test void preservesMariaDbFallbackAndExplicitDbSelection() {
        var node=BasicConfigurationNode.root();
        node.node("UseMariaDB").raw(true);
        node.node("MaxConnections").raw(0);
        node.node("Name").raw("votes");
        MysqlConfigView maria=new MysqlConfigView(new ConfigurateConfigView(node));
        assertEquals(DbType.MARIADB, maria.getDbType());
        assertEquals(1, maria.getMaxThreads());
        assertEquals("votes", maria.getTableName());
        node.node("DbType").raw("POSTGRESQL");
        assertEquals(DbType.fromString("POSTGRESQL"), new MysqlConfigView(new ConfigurateConfigView(node)).getDbType());
    }
    @Test void loadsTheExistingQueryAndConnectionApiWithoutBukkit() throws Exception {
        var project = Query.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = SharedRuntimeClasspath.open(project)) {
            SharedRuntimeClasspath.requirePlatformsAbsent(loader);
            for (String name : new String[] {ConnectionManager.class.getName(), AbstractSqlTable.class.getName(), Query.class.getName()})
                assertDoesNotThrow(() -> loader.loadClass(name).getDeclaredMethods());
        }
    }
}
