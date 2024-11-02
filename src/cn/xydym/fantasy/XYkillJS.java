package cn.xydym.fantasy;

import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.ChatColor;
import org.bukkit.plugin.RegisteredServiceProvider;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.ConfigurationSection;


import java.io.*;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

public class XYkillJS extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private double min;
    private double max;
    private String message;
    private Economy economy;
    private Map<EntityType, Double> monsterLimits = new HashMap<>();
    private Map<String, Map<EntityType, Double>> playerDailyEarnings = new HashMap<>();
    private Map<EntityType, String> monsterMessages = new HashMap<>();
    private Map<String, Map<EntityType, Boolean>> playerMessageSent = new HashMap<>();
    private File dailyLimitFile;
    private YamlConfiguration dailyLimitConfig;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.loadConfig();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.setupEconomy();
        Bukkit.getLogger().info("XYkillJS插件已加载！");

        // 初始化 dailyLimitFile 和 dailyLimitConfig
        dailyLimitFile = new File(getDataFolder(), "MonsterSETlimit.yml");
        if (!dailyLimitFile.exists()) {
            try {
                // 尝试创建文件
                if (dailyLimitFile.createNewFile()) {
                    Bukkit.getLogger().info("MonsterSETlimit.yml 每日奖励上限数据文件已创建！");
                    // 写入注释并指定编码为 UTF-8
                    try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(dailyLimitFile), StandardCharsets.UTF_8)) {
                        writer.write("#Fantasy每日上限数据文件A(请不要动这行备注)\n");
                    }
                } else {
                    Bukkit.getLogger().warning("无法创建 MonsterSETlimit.yml 文件！");
                }
            } catch (IOException e) {
                Bukkit.getLogger().severe("创建 MonsterSETlimit.yml 文件时发生错误: " + e.getMessage());
            }
        }
        dailyLimitConfig = YamlConfiguration.loadConfiguration(dailyLimitFile);

        // 加载每日上限数据
        loadDailyLimits();

        // 安排每天深夜12点重置 playerDailyEarnings
        scheduleDailyReset();

        // 安排每5分钟保存一次数据
        schedulePeriodicSave();
    }

    private void schedulePeriodicSave() {
        new BukkitRunnable() {
            @Override
            public void run() {
                saveDailyLimits();
            }
        }.runTaskTimer(this, 20 * 60 * 5, 20 * 60 * 5);
    }

    @Override
    public void onDisable() {
        // 保存每日上限数据
        saveDailyLimits();
        Bukkit.getLogger().info("每日奖励上限数据文件已保存到 MonsterSETlimit.yml ");
        // 显式关闭 dailyLimitConfig
        try {
            dailyLimitConfig.save(dailyLimitFile);
        } catch (IOException e) {
            getLogger().severe("无法保存 MonsterSETlimit.yml 每日奖励上限数据文件: " + e.getMessage());
        }
        // 取消所有定时任务
        Bukkit.getScheduler().cancelTasks(this);
    }

    private void loadConfig() {
        this.reloadConfig();
        FileConfiguration config = getConfig();
        monsterLimits.clear();

        config.getConfigurationSection("MonsterSET").getKeys(false).forEach(mobString -> {
            try {
                EntityType entityType = EntityType.valueOf(mobString.toUpperCase());
                double limit = config.getDouble("MonsterSET." + mobString + ".limit");
                String message = ChatColor.translateAlternateColorCodes('&', config.getString("MonsterSET." + mobString + ".message"));
                monsterLimits.put(entityType, limit);
                monsterMessages.put(entityType, message);
            } catch (IllegalArgumentException e) {
                getLogger().warning("配置文件中无效的实体类型: " + mobString);
            }
        });

        this.min = config.getDouble("min");
        this.max = config.getDouble("max");
        this.message = ChatColor.translateAlternateColorCodes('&', config.getString("message"));
    }

    private void setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        this.economy = rsp == null ? null : rsp.getProvider();
    }

    private void loadDailyLimits() {
        for (String playerName : dailyLimitConfig.getKeys(false)) {
            Map<EntityType, Double> earnings = new HashMap<>();
            for (String entityTypeString : dailyLimitConfig.getConfigurationSection(playerName).getKeys(false)) {
                try {
                    EntityType entityType = EntityType.valueOf(entityTypeString.toUpperCase());
                    double earningsValue = dailyLimitConfig.getDouble(playerName + "." + entityTypeString);
                    earnings.put(entityType, earningsValue);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("MonsterSETlimit.yml 中无效的实体类型: " + entityTypeString);
                }
            }
            playerDailyEarnings.put(playerName, earnings);
        }
    }

    private void saveDailyLimits() {
        for (Map.Entry<String, Map<EntityType, Double>> entry : playerDailyEarnings.entrySet()) {
            String playerName = entry.getKey();
            Map<EntityType, Double> earnings = entry.getValue();
            for (Map.Entry<EntityType, Double> earningsEntry : earnings.entrySet()) {
                dailyLimitConfig.set(playerName + "." + earningsEntry.getKey().name(), earningsEntry.getValue());
            }
        }
        try {
            dailyLimitConfig.save(dailyLimitFile);
        } catch (IOException e) {
            getLogger().severe("无法保存 MonsterSETlimit.yml 文件: " + e.getMessage());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player != null && economy != null) {
            EntityType entityType = event.getEntityType();
            if (monsterLimits.containsKey(entityType)) {
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    double reward = Math.round((random.nextDouble() * (max - min) + min) * 10.0) / 10.0;
                    double dailyLimit = monsterLimits.get(entityType);
                    String playerName = player.getName();

                    // 初始化玩家的收益和提示状态
                    playerDailyEarnings.putIfAbsent(playerName, new HashMap<>());
                    playerMessageSent.putIfAbsent(playerName, new HashMap<>());

                    Map<EntityType, Double> earnings = playerDailyEarnings.get(playerName);
                    Map<EntityType, Boolean> messageSent = playerMessageSent.get(playerName);

                    double currentEarnings = earnings.getOrDefault(entityType, 0.0);
                    boolean hasSentMessage = messageSent.getOrDefault(entityType, false);

                    if (currentEarnings + reward > dailyLimit) {
                        reward = dailyLimit - currentEarnings;
                        reward = Math.round(reward * 10.0) / 10.0; // 确保奖励没有过长的小数
                        if (reward <= 0 && !hasSentMessage) {
                            player.sendMessage(monsterMessages.get(entityType));
                            messageSent.put(entityType, true); // 发送提示后设置标志
                            return;
                        }
                    }
                    if (reward > 0) {
                        economy.depositPlayer(player, reward);
                        earnings.put(entityType, currentEarnings + reward);
                        player.sendMessage(message.replace("{reward}", String.valueOf(reward)));
                    } else if (!hasSentMessage) {
                        player.sendMessage(monsterMessages.get(entityType));
                        messageSent.put(entityType, true); // 发送提示后设置标志
                    }
                });
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("xykjsreload")) {
            if (sender.hasPermission("xykilljs.reload")) {
                loadConfig();
                sender.sendMessage(ChatColor.GREEN + "XYkillJS插件已重新加载!");
            } else {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令!");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("xykjsredayset")) {
            if (sender.hasPermission("xykilljs.reset")) {
                resetDailyLimits();
                sender.sendMessage(ChatColor.GREEN + "每日数据已提前重置!");
            } else {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令!");
            }
            return true;
        }
        return false;
    }

    private void resetDailyLimits() {
        playerDailyEarnings.clear(); // 清空玩家收益记录
        playerMessageSent.clear(); // 清空消息发送记录

        // 清空配置文件
        ConfigurationSection rootSection = dailyLimitConfig.getRoot();
        if (rootSection != null) {
            rootSection.getKeys(false).forEach(key -> rootSection.set(key, null));
        }

        try {
            // 保存配置文件
            dailyLimitConfig.save(dailyLimitFile);

            // 读取文件内容
            List<String> lines = Files.readAllLines(dailyLimitFile.toPath(), StandardCharsets.UTF_8);

            // 在第一行插入备注
            lines.add(0, "#Fantasy每日上限数据文件C(请不要动这行备注)");

            // 写回文件
            Files.write(dailyLimitFile.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("无法保存 MonsterSETlimit.yml 文件: " + e.getMessage());
        }
    }

    private void scheduleDailyReset() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        Date midnight = calendar.getTime();
        long delay = midnight.getTime() - System.currentTimeMillis();

        new BukkitRunnable() {
            @Override
            public void run() {
                playerDailyEarnings.clear(); // 清空玩家收益记录
                playerMessageSent.clear(); // 清空消息发送记录

                // 清空配置文件
                ConfigurationSection rootSection = dailyLimitConfig.getRoot();
                if (rootSection != null) {
                    rootSection.getKeys(false).forEach(key -> rootSection.set(key, null));
                }

                try {
                    // 保存配置文件
                    dailyLimitConfig.save(dailyLimitFile);

                    // 读取文件内容
                    List<String> lines = Files.readAllLines(dailyLimitFile.toPath(), StandardCharsets.UTF_8);

                    // 在第一行插入备注
                    lines.add(0, "#Fantasy每日上限数据文件C(请不要动这行备注)");

                    // 写回文件
                    Files.write(dailyLimitFile.toPath(), lines, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    getLogger().severe("无法保存 MonsterSETlimit.yml 文件: " + e.getMessage());
                }

                scheduleDailyReset(); // 重新安排下一次重置
            }
        }.runTaskLater(this, delay / 50); // 转换为 ticks
    }
}