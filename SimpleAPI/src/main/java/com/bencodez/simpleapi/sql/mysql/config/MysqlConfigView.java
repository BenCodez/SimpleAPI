package com.bencodez.simpleapi.sql.mysql.config;

import com.bencodez.simpleapi.file.config.ConfigView;

/** Compatibility name for the shared MySQL configuration adapter. */
public final class MysqlConfigView extends com.bencodez.simpleapi.core.sql.MysqlConfigView {
    public MysqlConfigView(ConfigView section) { super(section); }
}
