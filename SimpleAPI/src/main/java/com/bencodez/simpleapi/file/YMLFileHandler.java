package com.bencodez.simpleapi.file;

import java.io.File;

import org.bukkit.plugin.java.JavaPlugin;

import com.bencodez.simpleapi.exception.FileDirectoryException;

public class YMLFileHandler extends YMLFile {
	@SuppressWarnings("unused")
	private File file;

	public YMLFileHandler(JavaPlugin plugin, File file) {
		super(plugin, file);
		this.file = file;
		if (file.isDirectory()) {
			try {
				throw new FileDirectoryException(file.getAbsolutePath() + " must be a file");
			} catch (FileDirectoryException e) {
				e.printStackTrace();
			}
		}
	}

	@SuppressWarnings("deprecation")
	public void header(String string) {
		getData().options().header(string);
	}

	@Override
	public void onFileCreation() {

	}
}