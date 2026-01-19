package me.ariver.helloplugin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HelloPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HelloPlugin включен!");
    }

    @Override
    public void onDisable() {
        getLogger().info("HelloPlugin выключен!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("hello")) {
            sender.sendMessage("Пососите яйца первый плагин.");
            return true;
        
        }

        if (command.getName().equalsIgnoreCase("info")) {

            // Проверяем, что команду ввёл игрок
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Эта команда только для игроков.");
                return true;
            }

            // Ник игрока
            String playerName = player.getName();

            // Версия сервера Minecraft
            String minecraftVersion = Bukkit.getMinecraftVersion();

            // Текущая дата
            String date = LocalDate.now()
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

            player.sendMessage("§6--- Информация ---");
            player.sendMessage("§eИгрок: §f" + playerName);
            player.sendMessage("§eВерсия Minecraft: §f" + minecraftVersion);
            player.sendMessage("§eДата: §f" + date);

            return true;
        }

        return false;
    }
}
