package com.bencodez.simpleapi.serverhandle;

import org.bukkit.entity.Player;

import net.md_5.bungee.api.chat.BaseComponent;

public interface IServerHandle {
	public void sendMessage(Player player, BaseComponent component);

	public void sendMessage(Player player, BaseComponent... components);
}
