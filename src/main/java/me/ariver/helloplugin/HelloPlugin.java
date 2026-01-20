package me.ariver.helloplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HelloPlugin extends JavaPlugin implements PluginMessageListener {

    private static final String KINO_CHANNEL = "kino:main";

    // Кто сейчас в “кинозале”
    private final Set<UUID> viewers = new HashSet<>();

    // Что сейчас “проигрывается”
    private String currentUrl = null;
    private boolean playing = false;

    @Override
    public void onEnable() {
        getLogger().info("HelloPlugin включен!");

        // Канал для связи с клиентским модом
        getServer().getMessenger().registerOutgoingPluginChannel(this, KINO_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, KINO_CHANNEL, this);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KINO_CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, KINO_CHANNEL);

        getLogger().info("HelloPlugin выключен!");
    }

    /**
     * Приём сообщений от клиентского мода (пока просто логируем для отладки).
     * Клиент позже сможет отправлять, например: "ACK|PLAYING" или "ERR|..."
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!KINO_CHANNEL.equals(channel)) return;

        String text = new String(message, StandardCharsets.UTF_8);
        getLogger().info("[KINO <- " + player.getName() + "] " + text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // --- /hello ---
        if (command.getName().equalsIgnoreCase("hello")) {
            sender.sendMessage("Привет! Плагин работает.");
            return true;
        }

        // --- /info ---
        if (command.getName().equalsIgnoreCase("info")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Эта команда только для игроков.");
                return true;
            }

            String playerName = player.getName();
            String minecraftVersion = Bukkit.getMinecraftVersion();
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

            player.sendMessage("§6--- Информация ---");
            player.sendMessage("§eИгрок: §f" + playerName);
            player.sendMessage("§eВерсия Minecraft: §f" + minecraftVersion);
            player.sendMessage("§eДата: §f" + date);
            return true;
        }

        // --- /kino ---
        if (command.getName().equalsIgnoreCase("kino")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Эта команда только для игроков.");
                return true;
            }

            if (args.length == 0) {
                sendKinoHelp(player);
                return true;
            }

            String sub = args[0].toLowerCase();

            switch (sub) {
                case "join" -> {
                    viewers.add(player.getUniqueId());
                    player.sendMessage("§aТы вошёл в кинозал. Зрителей: §f" + viewers.size());

                    // Если кино уже идёт — отправим текущий сеанс конкретно этому игроку
                    if (playing && currentUrl != null) {
                        player.sendMessage("§eСейчас идёт сеанс: §f" + currentUrl);
                        sendToViewer(player, "PLAY|" + currentUrl);
                    }
                    return true;
                }

                case "leave" -> {
                    viewers.remove(player.getUniqueId());
                    player.sendMessage("§cТы вышел из кинозала. Зрителей: §f" + viewers.size());
                    return true;
                }

                case "play" -> {
                    if (!viewers.contains(player.getUniqueId())) {
                        player.sendMessage("§cСначала войди в кинозал: §f/kino join");
                        return true;
                    }

                    if (args.length < 2) {
                        player.sendMessage("§cИспользование: §f/kino play <direct-mp4-url>");
                        player.sendMessage("§7Пример: /kino play https://example.com/video.mp4");
                        return true;
                    }

                    String url = args[1];
                    currentUrl = url;
                    playing = true;

                    broadcastToViewers("§6[Кино] §aЗапуск сеанса: §f" + url);

                    // Главное: отправляем клиентскому моду команду проигрывания
                    broadcastPacketToViewers("PLAY|" + url);

                    return true;
                }

                case "stop" -> {
                    if (!viewers.contains(player.getUniqueId())) {
                        player.sendMessage("§cСначала войди в кинозал: §f/kino join");
                        return true;
                    }

                    if (!playing) {
                        player.sendMessage("§eСеанс не запущен.");
                        return true;
                    }

                    playing = false;
                    String was = currentUrl;
                    currentUrl = null;

                    broadcastToViewers("§6[Кино] §cСеанс остановлен." + (was != null ? " §7(" + was + ")" : ""));

                    // Команда стоп клиентскому моду
                    broadcastPacketToViewers("STOP|");

                    return true;
                }

                case "status" -> {
                    player.sendMessage("§6--- Кинотеатр ---");
                    player.sendMessage("§eЗрителей в зале: §f" + viewers.size());
                    player.sendMessage("§eСтатус: §f" + (playing ? "Идёт сеанс" : "Остановлено"));
                    player.sendMessage("§eURL: §f" + (currentUrl == null ? "-" : currentUrl));
                    return true;
                }

                default -> {
                    sendKinoHelp(player);
                    return true;
                }
            }
        }

        return false;
    }

    private void sendKinoHelp(Player player) {
        player.sendMessage("§6--- /kino ---");
        player.sendMessage("§e/kino join §7- войти в зал");
        player.sendMessage("§e/kino leave §7- выйти из зала");
        player.sendMessage("§e/kino play <url> §7- запустить сеанс");
        player.sendMessage("§e/kino stop §7- остановить сеанс");
        player.sendMessage("§e/kino status §7- статус кинотеатра");
    }

    private void broadcastToViewers(String message) {
        for (UUID uuid : viewers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    /**
     * Отправка пакета конкретному игроку (через его соединение).
     */
    private void sendToViewer(Player player, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        player.sendPluginMessage(this, KINO_CHANNEL, data);
    }

    /**
     * Рассылка пакета всем зрителям, кто сейчас онлайн.
     */
    private void broadcastPacketToViewers(String payload) {
        for (UUID uuid : viewers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                sendToViewer(p, payload);
            }
        }
    }
}
