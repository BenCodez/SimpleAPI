package com.bencodez.simpleapi.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.bencodez.simpleapi.player.PlayerUtils;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

// TODO: Auto-generated Javadoc
/**
 * The Class CommandHandler.
 */
public abstract class CommandHandler {

	@Getter
	@Setter
	private boolean advancedCoreCommand = false;

	@Getter
	@Setter
	private boolean allowConsole = true;

	@Getter
	@Setter
	private boolean allowMultiplePermissions = true;

	@Getter
	@Setter
	private String[] args;

	@Getter
	@Setter
	private boolean forceConsole = false;

	@Getter
	@Setter
	private String helpMessage;

	@Getter
	private boolean ignoreNumberCheck = false;

	@Getter
	@Setter
	private String perm = "";

	@Getter
	private JavaPlugin plugin;

	public CommandHandler(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	/**
	 * Instantiates a new command handler.
	 *
	 * @param plugin main pluginclass
	 * @param args   the args
	 * @param perm   the perm
	 */
	public CommandHandler(JavaPlugin plugin, String[] args, String perm) {
		this.plugin = plugin;
		this.args = args;
		this.perm = perm;
		helpMessage = "Unknown Help Message";

	}

	/**
	 * Instantiates a new command handler.
	 *
	 * @param plugin      main pluginclass
	 * @param args        the args
	 * @param perm        the perm
	 * @param helpMessage the help message
	 */
	public CommandHandler(JavaPlugin plugin, String[] args, String perm, String helpMessage) {
		this.plugin = plugin;
		this.args = args;
		this.perm = perm;
		this.helpMessage = helpMessage;
	}

	/**
	 * Instantiates a new command handler.
	 *
	 * @param plugin       main pluginclass
	 * @param args         the args
	 * @param perm         the perm
	 * @param helpMessage  the help message
	 * @param allowConsole the allow console
	 */
	public CommandHandler(JavaPlugin plugin, String[] args, String perm, String helpMessage, boolean allowConsole) {
		this.plugin = plugin;
		this.args = args;
		this.perm = perm;
		this.helpMessage = helpMessage;
		this.allowConsole = allowConsole;
	}

	/**
	 * Instantiates a new command handler.
	 *
	 * @param plugin       main pluginclass
	 * @param args         the args
	 * @param perm         the perm
	 * @param helpMessage  the help message
	 * @param allowConsole the allow console
	 * @param forceConsole Option to force console only command
	 */
	public CommandHandler(JavaPlugin plugin, String[] args, String perm, String helpMessage, boolean allowConsole,
			boolean forceConsole) {
		this.plugin = plugin;
		this.args = args;
		this.perm = perm;
		this.helpMessage = helpMessage;
		this.allowConsole = allowConsole;
		this.forceConsole = forceConsole;
	}

	/**
	 * Adds the tab complete option.
	 *
	 * @param toReplace the to replace
	 * @param options   the options
	 */
	@Deprecated
	public void addTabCompleteOption(String toReplace, ArrayList<String> options) {
		TabCompleteHandler.getInstance().addTabCompleteOption(toReplace, options);
	}

	/**
	 * Adds the tab complete option.
	 *
	 * @param toReplace the to replace
	 * @param options   the options
	 */
	@Deprecated
	public void addTabCompleteOption(String toReplace, String... options) {
		addTabCompleteOption(toReplace, ArrayUtils.convert(options));
	}

	/**
	 * Args match.
	 *
	 * @param arg the arg
	 * @param i   the i
	 * @return true, if successful
	 */
	public boolean argsMatch(String arg, int i) {
		if (i < args.length) {
			String[] cmdArgs = args[i].split("&");
			for (String cmdArg : cmdArgs) {
				if (arg.equalsIgnoreCase(cmdArg)) {
					return true;
				}

				for (String str : TabCompleteHandler.getInstance().getTabCompleteReplaces()) {
					if (str.equalsIgnoreCase(cmdArg)) {
						return true;
					}
				}

			}
			// plugin.debug("Tab: "
			// + Utils.getInstance().makeStringList(
			// Utils.getInstance().convert(
			// tabCompleteOptions.keySet())) + " "
			// + args[i]);
			for (String str : TabCompleteHandler.getInstance().getTabCompleteReplaces()) {
				if (str.equalsIgnoreCase(args[i])) {
					return true;
				}
			}
			return false;
		}
		if (args[args.length - 1].equalsIgnoreCase("(list)")) {
			return true;
		}
		return false;
	}

	public abstract void debug(String debug);

	/**
	 * Execute.
	 *
	 * @param sender the sender
	 * @param args   the args
	 */
	public abstract void execute(CommandSender sender, String[] args);

	public abstract String formatNoPerms();

	public abstract String formatNotNumber();

	public abstract BukkitScheduler getBukkitScheduler();

	public abstract String getHelpLine();

	/**
	 * Gets the help line.
	 *
	 * @param command the command
	 * @return the help line
	 */
	@SuppressWarnings("deprecation")
	public TextComponent getHelpLine(String command) {
		String line = getHelpLine();

		String commandText = getHelpLineCommand(command);
		line = line.replace("%Command%", commandText);
		if (getHelpMessage() != "") {
			line = line.replace("%HelpMessage%", getHelpMessage());
		}
		TextComponent txt = MessageAPI.stringToComp(line);
		txt.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, commandText));
		txt.setHoverEvent(MessageAPI.getHoverEventSupport()
				.createHoverEvent(TextComponent.fromLegacyText(ChatColor.AQUA + getHelpMessage())));
		return txt;

	}

	@SuppressWarnings("deprecation")
	public TextComponent getHelpLine(String command, String line) {
		String commandText = getHelpLineCommand(command);
		line = line.replace("%Command%", commandText);
		if (getHelpMessage() != "") {
			line = line.replace("%HelpMessage%", getHelpMessage());
		}
		TextComponent txt = MessageAPI.stringToComp(line);
		txt.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, commandText));
		txt.setHoverEvent(MessageAPI.getHoverEventSupport()
				.createHoverEvent(TextComponent.fromLegacyText(ChatColor.AQUA + getHelpMessage())));
		return txt;
	}

	@SuppressWarnings("deprecation")
	public TextComponent getHelpLine(String command, String line, ChatColor hoverColor) {
		String commandText = getHelpLineCommand(command);
		line = line.replace("%Command%", commandText);
		if (getHelpMessage() != "") {
			line = line.replace("%HelpMessage%", getHelpMessage());
		}
		TextComponent txt = MessageAPI.stringToComp(line);
		txt.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, commandText));
		txt.setHoverEvent(MessageAPI.getHoverEventSupport()
				.createHoverEvent(TextComponent.fromLegacyText(hoverColor + getHelpMessage())));
		return txt;
	}

	/**
	 * Gets the help line command.
	 *
	 * @param command the command
	 * @return the help line command
	 */
	public String getHelpLineCommand(String command) {
		String commandText = command;
		boolean addSpace = true;
		if (command.isEmpty()) {
			addSpace = false;
		}
		for (String arg1 : args) {
			int count = 1;
			for (String arg : arg1.split("&")) {
				if (count == 1) {
					if (addSpace) {
						commandText += " " + arg;
					} else {
						commandText += arg;
						addSpace = true;
					}
				} else {
					commandText += "/" + arg;
				}
				count++;
			}
		}
		return commandText;
	}

	public ArrayList<String> getTabCompleteOptions(CommandSender sender, String[] args, int argNum,
			ConcurrentHashMap<String, ArrayList<String>> tabCompleteOptions) {
		Set<String> cmds = new HashSet<>();
		if (hasPerm(sender)) {
			CommandHandler commandHandler = this;

			String[] cmdArgs = commandHandler.getArgs();
			if (cmdArgs.length > argNum) {
				boolean argsMatch = true;
				for (int i = 0; i < argNum; i++) {
					if (args.length >= i) {
						if (!commandHandler.argsMatch(args[i], i)) {
							argsMatch = false;
						}
					}
				}

				if (argsMatch) {
					String[] cmdArgsList = cmdArgs[argNum].split("&");

					for (String arg : cmdArgsList) {
						// plugin.debug(arg);
						boolean add = true;
						for (Entry<String, ArrayList<String>> entry : tabCompleteOptions.entrySet()) {
							if (arg.equalsIgnoreCase(entry.getKey())) {
								add = false;
								cmds.addAll(entry.getValue());
							}
						}
						if (!cmds.contains(arg) && add) {
							cmds.add(arg);
						}
					}

				}

			}
		}

		ArrayList<String> options = ArrayUtils.convert(cmds);

		Collections.sort(options, String.CASE_INSENSITIVE_ORDER);

		return options;
	}

	public boolean hasArg(String arg) {
		for (String str : getArgs()) {
			if (str.equalsIgnoreCase(arg)) {
				return true;
			}
		}
		return false;
	}

	public boolean hasPerm(CommandSender sender) {
		if (getPerm().isEmpty()) {
			return true;
		}
		if (allowMultiplePermissions) {
			return PlayerUtils.hasEitherPermission(sender, getPerm());
		}
		return sender.hasPermission(getPerm().split(Pattern.quote("|"))[0]);
	}

	public CommandHandler ignoreNumberCheck() {
		ignoreNumberCheck = true;
		return this;
	}

	public boolean isCommand(String arg) {
		if (getArgs().length > 0) {
			for (String str : getArgs()[0].split("&")) {
				if (str.equalsIgnoreCase(arg)) {
					return true;
				}
			}

		} else if (arg.isEmpty() && getArgs().length == 0) {
			return true;
		}
		return false;
	}

	public boolean isPlayer(CommandSender sender) {
		return sender instanceof Player;
	}

	public CommandHandler noConsole() {
		this.allowConsole = false;
		return this;
	}

	public int parseInt(String arg) {
		return Integer.parseInt(arg);
	}

	/**
	 * Run command.
	 *
	 * @param sender the sender
	 * @param args   the args
	 * @return true, if successful
	 */
	public boolean runCommand(CommandSender sender, String[] args) {
		if (args.length >= this.args.length) {
			if (this.args.length != args.length && !hasArg("(list)")) {
				return false;
			}
			for (int i = 0; i < args.length && i < this.args.length; i++) {
				if (!argsMatch(args[i], i)) {
					return false;
				}
				if (this.args[i].equalsIgnoreCase("(number)")) {
					if (!ignoreNumberCheck && !MessageAPI.isInt(args[i])) {
						sender.sendMessage(MessageAPI.colorize(formatNotNumber().replace("%arg%", args[i])));
						return true;
					}
				} else if (this.args[i].equalsIgnoreCase("(Player)")) {
					if (args[i].equalsIgnoreCase("@p")) {
						args[i] = sender.getName();
					} else if (args[i].equalsIgnoreCase("@r")) {
						args[i] = PlayerUtils.getRandomOnlinePlayer().getName();
					} else {
						Player p = Bukkit.getPlayer(args[i]);
						if (p == null) {
							for (Player player : Bukkit.getOnlinePlayers()) {
								String name = player.getName();
								if (MessageAPI.containsIgnorecase(name, args[i])) {
									debug("Completing name: " + args[i] + " to " + name);
									args[i] = name;
									break;
								}
							}
						} else {
							if (args[i] != p.getName()) {
								args[i] = p.getName();
							}
						}
					}
				}
			}
			if (!(sender instanceof Player) && !allowConsole) {
				sender.sendMessage(MessageAPI.colorize("&cMust be a player to do this"));
				return true;
			}

			if (sender instanceof Player && forceConsole) {
				sender.sendMessage(MessageAPI.colorize("&cConsole command only"));
				return true;
			}

			if (!hasPerm(sender)) {
				if (!formatNoPerms().isEmpty()) {
					sender.sendMessage(MessageAPI.colorize(formatNoPerms()));
				}
				plugin.getLogger().log(Level.INFO,
						sender.getName() + " was denied access to command, required permission: " + perm);
				return true;
			}

			if (args.length > 0 && args[0].equalsIgnoreCase("AdvancedCore")) {
				String[] list = new String[args.length - 1];
				for (int i = 1; i < args.length; i++) {
					list[i - 1] = args[i];
				}
				args = list;
			}
			String[] argsNew = args;

			getBukkitScheduler().runTaskAsynchronously(plugin, new Runnable() {

				@Override
				public void run() {
					execute(sender, argsNew);
				}
			});

			return true;
		}
		return false;
	}

	public void sendMessage(CommandSender sender, ArrayList<String> msg) {
		sender.sendMessage(ArrayUtils.convert(ArrayUtils.colorize(msg)));
	}

	public void sendMessage(CommandSender sender, String msg) {
		sender.sendMessage(MessageAPI.colorize(msg));
	}

	public void sendMessageJson(CommandSender sender, ArrayList<TextComponent> comp) {
		if (isPlayer(sender)) {
			Player player = (Player) sender;
			MessageAPI.sendJson(player, comp);
		} else {
			sender.sendMessage(ArrayUtils.convert(ArrayUtils.comptoString(comp)));
		}
	}

	public void sendMessageJson(CommandSender sender, TextComponent comp) {
		if (isPlayer(sender)) {
			Player player = (Player) sender;
			MessageAPI.sendJson(player, comp);
		} else {
			sender.sendMessage(MessageAPI.compToString(comp));
		}
	}

	public CommandHandler withArgs(String... args) {
		this.args = args;
		return this;
	}

	public CommandHandler withHelpMessage(String helpMessage) {
		this.helpMessage = helpMessage;
		return this;
	}

	public CommandHandler withPerm(String perm) {
		if (!this.perm.isEmpty()) {
			this.perm = this.perm + "|" + perm;
		} else {
			this.perm = perm;
		}
		return this;
	}

	public CommandHandler withPerm(String perm, boolean add) {
		if (!add) {
			return this;
		}
		if (!this.perm.isEmpty()) {
			this.perm = this.perm + "|" + perm;
		} else {
			this.perm = perm;
		}
		return this;
	}

}
