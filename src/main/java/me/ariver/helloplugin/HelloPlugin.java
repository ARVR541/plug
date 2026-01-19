package me.ariver.helloplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
        return false;
    }
}
