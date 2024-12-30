package com.bencodez.simpleapi.command;

import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.Setter;

public abstract class TabCompleteHandle {
	@Getter
	@Setter
	private ArrayList<String> replace = new ArrayList<>();

	@Getter
	@Setter
	private String toReplace;

	@Getter
	private boolean updateOnLoginLogout = false;

	public TabCompleteHandle(String toReplace) {
		this.toReplace = toReplace;
		reload();
	}

	public TabCompleteHandle(String toReplace, ArrayList<String> replace) {
		this.toReplace = toReplace;
		this.replace = replace;
	}

	public abstract void reload();

	public TabCompleteHandle updateEveryXMinutes(ScheduledExecutorService timer, int x) {
		timer.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				updateReplacements();
			}
		}, x, x, TimeUnit.SECONDS);
		return this;
	}

	public TabCompleteHandle updateOnLoginLogout() {
		updateOnLoginLogout = true;
		return this;
	}

	public abstract void updateReplacements();
}
