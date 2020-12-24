package me.scruffyboy13.Economy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.logging.Level;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class DataManager {
	
	private FileConfiguration dataConfig = null;
	private File configFile = null;
	private String fileName = null;
	private String path = null;
  
	public DataManager(String fileName, String path) {
		this.fileName  = fileName;
		this.path = path;
		saveDefaultConfig();
	}
  
	public void reloadConfig() {
		if (configFile == null)
			configFile = new File(path, fileName);
		dataConfig = (FileConfiguration)YamlConfiguration.loadConfiguration(configFile);
		InputStream defaultStream = Economy.getInstance().getResource(fileName);
		if (defaultStream != null) {
			YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
			dataConfig.setDefaults((Configuration)defaultConfig);
		} 
	}
  
	public FileConfiguration getConfig() {
		if (dataConfig == null)
			reloadConfig(); 
		return dataConfig;
	}
  
	public void saveConfig() {
		if (dataConfig == null || configFile == null)
			return; 
		try {
			getConfig().save(configFile);
		} catch (IOException e) {
			Economy.getInstance().getLogger().log(Level.SEVERE, "Could not save config to " + configFile, e);
		} 
	}
  
	public void saveDefaultConfig() {
		if (configFile == null)
			configFile = new File(path, fileName);
		try {
			configFile.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (!configFile.exists())
			Economy.getInstance().saveResource(path + "/" + fileName, false);
	}
}
