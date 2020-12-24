package me.scruffyboy13.Economy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.scruffyboy13.Economy.commands.BalanceCommand;
import me.scruffyboy13.Economy.commands.PayCommand;
import me.scruffyboy13.Economy.commands.BalanceTopCommand;
import me.scruffyboy13.Economy.commands.money.MoneyCommandHandler;
import me.scruffyboy13.Economy.iterators.PlayerManagerIterator;
import me.scruffyboy13.Economy.listeners.PlayerJoinListener;
import me.scruffyboy13.Economy.utils.FileUtils;
import me.scruffyboy13.Economy.utils.SQLUtils;

public class Economy extends JavaPlugin {

	private static Economy instance;
	private static EconomyCore economyCore;
	private static MySQL sql;
	private static MoneyCommandHandler moneyCommandHandler;
	private static Map<UUID, PlayerManager> playerManagerMap = new HashMap<>();
	private static Map<UUID, PlayerManager> sortedPlayerManagerMap = new HashMap<>();
	private static Map<String, String> sqlColumns = new HashMap<>();
	private static BukkitRunnable savePlayerDataRunnable;
	private static BukkitRunnable balanceTopRunnable;
	
	@Override
	public void onEnable() {
		
		saveDefaultConfig();
		
		sqlColumns.put("Balance", "DECIMAL(65, 1) NOT NULL DEFAULT " + getConfig().getDouble("startingBalance"));
		
		instance = this;
		economyCore = new EconomyCore();

		if (!setupEconomy()) {
			this.getLogger().warning("Economy couldn't be registed, Vault plugin is missing!");
			Bukkit.getPluginManager().disablePlugin(this);
			return;
		}
		this.getLogger().info("Vault found, Economy has been registered.");
		
		moneyCommandHandler = new MoneyCommandHandler();
		this.getCommand("money").setExecutor(moneyCommandHandler);
		this.getCommand("money").setTabCompleter(moneyCommandHandler);
		
		this.getCommand("balance").setExecutor(new BalanceCommand());
		this.getCommand("pay").setExecutor(new PayCommand());
		this.getCommand("balancetop").setExecutor(new BalanceTopCommand());
		
		registerListeners(
				new PlayerJoinListener()
				);

		if (getConfig().getBoolean("mysql.use-mysql")) {
			
			sql = new MySQL(
					getConfig().getString("mysql.host"), 
					getConfig().getInt("mysql.port"), 
					getConfig().getString("mysql.database"), 
					getConfig().getString("mysql.username"), 
					getConfig().getString("mysql.password"));
			
			connectToSQL();
			
			if (sql.isConnected()) {
				try {
					SQLUtils.createTable();
					playerManagerMap = SQLUtils.getPlayerDataFromDatabase();
				} catch (SQLException e) {
					this.getLogger().warning("There was an error with getting player balances from your mysql database.");
					Bukkit.getPluginManager().disablePlugin(this);
					return;
				}
				
			}
			else {
				return;
			}
			
			int interval = getConfig().getInt("SaveDataTimerInterval");
			savePlayerDataRunnable = new BukkitRunnable() {
				Iterator<PlayerManager> iterator = new PlayerManagerIterator(new ArrayList<>(playerManagerMap.values()));
				int accountsPerRun = getConfig().getInt("AccountsSavedPerRun");
				@Override
				public void run() {
					for (int i = 0; i < accountsPerRun; i++) {
						if (iterator.hasNext()) {
							try {
								if (sql.isConnected()) {
									SQLUtils.savePlayerToDatabase(iterator.next());
								}
								else {
									Economy.getInstance().getLogger().info("You were disconnected from the database, "
											+ "attempting to reconnect now.");
									connectToSQL();
								}
							} catch (SQLException | ConcurrentModificationException e) {
								Economy.getInstance().getLogger().warning("There was an error with saving to the database, "
										+ "this isn't a problem with the plugin.");
							}
						}
						else {
							iterator = new PlayerManagerIterator(
									new ArrayList<>(playerManagerMap.values()));
							return;
						}
					}
				}
			};
			savePlayerDataRunnable.runTaskTimerAsynchronously(this, 0, interval);
			
		}
		else {
			
			Path dataDir = Paths.get(getDataFolderPath() + "/data/");
			if (!Files.exists(dataDir)) {
				try {
					Files.createDirectory(dataDir);
				} catch (IOException e) {
					this.getLogger().warning("There was an error creating the data directory.");
		            this.getServer().getPluginManager().disablePlugin(this);
		            return;
				}
			}
			
			playerManagerMap = FileUtils.getPlayerDataFromDatabase();
			
			int interval = getConfig().getInt("SaveDataTimerInterval");
			savePlayerDataRunnable = new BukkitRunnable() {
				Iterator<PlayerManager> iterator = new PlayerManagerIterator(new ArrayList<>(playerManagerMap.values()));
				int islandsPerRun = getConfig().getInt("islandsSavedPerRun");
				@Override
				public void run() {
					for (int i = 0; i < islandsPerRun; i++) {
						if (iterator.hasNext()) {
							PlayerManager playerManager = iterator.next();
							FileUtils.saveToFile(FileUtils.getIslandFile(playerManager), playerManager);
						}
						else {
							iterator = new PlayerManagerIterator(
									new ArrayList<>(playerManagerMap.values()));
							return;
						}
					}
				}
			};
			savePlayerDataRunnable.runTaskTimerAsynchronously(this, 0, interval);
			
		}
		
		balanceTopRunnable = new BukkitRunnable() {
			Comparator<Entry<UUID, PlayerManager>> valueComparator = new Comparator<Entry<UUID, PlayerManager>>() {
				@Override public int compare(Entry<UUID, PlayerManager> e1, Entry<UUID, PlayerManager> e2) { 
					Double balance1 = e1.getValue().getBalance();
					Double balance2 = e2.getValue().getBalance();
					return balance2.compareTo(balance1);
				}
			};
			@Override
			public void run() {
				List<Entry<UUID, PlayerManager>> listOfPlayerManagers = 
						new ArrayList<Entry<UUID, PlayerManager>>(playerManagerMap.entrySet());
				Collections.sort(listOfPlayerManagers, valueComparator);
				sortedPlayerManagerMap = listOfPlayerManagers.stream().collect(Collectors.toMap(
						Map.Entry::getKey, Map.Entry::getValue, (v1,v2)->v1, LinkedHashMap::new));
			}
		};
		int interval = getConfig().getInt("BalanceTopTimerInterval");
		balanceTopRunnable.runTaskTimerAsynchronously(this, 0, interval);
	}

	@Override
	public void onDisable() {

		balanceTopRunnable.cancel();
		
		this.getLogger().info("Saving all accounts...");
		if (getConfig().getBoolean("mysql.use-mysql")) {
			for (PlayerManager playerManager : playerManagerMap.values()) {
				try {
					if (!sql.isConnected()) {
						connectToSQL();
					}
					SQLUtils.savePlayerToDatabase(playerManager);
				} catch (SQLException | ConcurrentModificationException e) {
					this.getLogger().warning("There was an error with saving to the database, "
							+ "this isn't a problem with the plugin.");
				}
			}
		}
		else {
			for (PlayerManager playerManager : playerManagerMap.values()) {
				FileUtils.saveToFile(FileUtils.getIslandFile(playerManager), playerManager);
			}
		}
		
	}
	
	public void connectToSQL() {
		try {
			sql.connect();
            this.getLogger().info("Successfully connected to your mysql database.");
        } 
        catch (SQLException e) {
			this.getLogger().warning("There was an error connecting to the database. " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        catch (ClassNotFoundException e) {
        	this.getLogger().warning("The MySQL driver class could not be found.");
        	Bukkit.getPluginManager().disablePlugin(this);
        	return;
        }
	}

	public static Economy getInstance() {
		return instance;
	}
	
	private boolean setupEconomy() {
		if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
			return false;
		}
		this.getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, 
				economyCore, this, ServicePriority.Highest);
		return true;
	}

	public static MySQL getSQL() {
		return sql;
	}

	public static Map<String, String> getSQLColumns() {
		return sqlColumns;
	}

	public static MoneyCommandHandler getMoneyCommandHandler() {
		return moneyCommandHandler;
	}
	
	public static EconomyCore getEconomyCore() {
		return economyCore;
	}

	public static Map<UUID, PlayerManager> getPlayerManagerMap() {
		return playerManagerMap;
	}
	
	private void registerListeners(Listener... listeners) {
		for (Listener listener : listeners) {
			Bukkit.getPluginManager().registerEvents(listener, this);
		}
	}

	public static BukkitRunnable getBalanceTopRunnable() {
		return balanceTopRunnable;
	}

	public static Map<UUID, PlayerManager> getSortedPlayerManagerMap() {
		return sortedPlayerManagerMap;
	}
	
	public static String getDataFolderPath() {
		return Economy.getInstance().getDataFolder().getAbsolutePath();
	}

	public static BukkitRunnable getSavePlayerDataRunnable() {
		return savePlayerDataRunnable;
	}
	
}
