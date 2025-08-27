package com.bencodez.simpleapi.scheduler;

import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.tcoded.folialib.FoliaLib;

import lombok.Getter;

public class BukkitScheduler {
	@Getter
	private FoliaLib foliaLib;

	public BukkitScheduler(JavaPlugin plugin) {
		foliaLib = new FoliaLib(plugin);
	}

	public void executeOrScheduleSync(Plugin plugin, Runnable task) {
		getFoliaLib().getImpl().runNextTick(run -> {
			task.run();
		});
	}

	public void executeOrScheduleSync(Plugin plugin, Runnable task, Entity entity) {
		if (entity == null) {
			executeOrScheduleSync(plugin, task);
			return;
		}
		getFoliaLib().getImpl().runAtEntity(entity, run -> {
			task.run();
		});
	}

	public void executeOrScheduleSync(Plugin plugin, Runnable task, Location location) {
		getFoliaLib().getImpl().runAtLocation(location, run -> {
			task.run();
		});
	}

	public void runTask(Plugin plugin, Runnable task) {
		getFoliaLib().getImpl().runNextTick(run -> {
			task.run();
		});
	}

	public void runTask(Plugin plugin, Runnable task, Entity entity) {
		if (entity == null) {
			runTask(plugin, task);
			return;
		}
		getFoliaLib().getImpl().runAtEntity(entity, run -> {
			task.run();
		});
	}

	public void runTask(Plugin plugin, Runnable task, Location location) {
		getFoliaLib().getImpl().runAtLocation(location, run -> {
			task.run();
		});
	}

	public void runTaskAsynchronously(Plugin plugin, Runnable task) {
		getFoliaLib().getImpl().runAsync(run -> {
			task.run();
		});
	}

	public void runTaskLater(Plugin plugin, Runnable task, long delay) {
		getFoliaLib().getImpl().runLater(run -> {
			task.run();
		}, delay, TimeUnit.SECONDS);
	}

	public void runTaskLater(Plugin plugin, Runnable task, long delay, Entity entity) {
		if (entity == null) {
			runTaskLater(plugin, task, delay);
			return;
		}
		getFoliaLib().getImpl().runAtEntityLater(entity, run -> {
			task.run();
		}, delay, TimeUnit.SECONDS);
	}

	public void runTaskLater(Plugin plugin, Runnable task, long delay, Location location) {
		getFoliaLib().getImpl().runAtLocationLater(location, run -> {
			task.run();
		}, delay, TimeUnit.SECONDS);
	}

	public void runTaskLater(Plugin plugin, Runnable task, long delay, TimeUnit time) {
		getFoliaLib().getImpl().runLater(run -> {
			task.run();
		}, delay, time);
	}

	public void runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
		getFoliaLib().getImpl().runLaterAsync(run -> {
			task.run();
		}, delay, TimeUnit.SECONDS);
	}

	public void runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
		getFoliaLib().getImpl().runTimer(run -> {
			task.run();
		}, delay, period, TimeUnit.SECONDS);
	}

	public void runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Entity entity) {
		if (entity == null) {
			runTaskTimer(plugin, task, delay, period);
			return;
		}
		getFoliaLib().getImpl().runTimer(run -> {
			task.run();
		}, delay, period, TimeUnit.SECONDS);
	}

	public void runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Location location) {
		getFoliaLib().getImpl().runAtLocationTimer(location, run -> {
			task.run();
		}, delay, period, TimeUnit.SECONDS);
	}

	public void runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
		getFoliaLib().getImpl().runTimerAsync(run -> {
			task.run();
		}, delay, period, TimeUnit.SECONDS);
	}

}