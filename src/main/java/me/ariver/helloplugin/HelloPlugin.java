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

    // Зрители кинозала
    private final Set<UUID> viewers = new HashSet<>();

    // Состояние сессии
    private String currentUrl = null;
    private boolean playing = false;

    // Важные поля для синхронизации:
    // serverPlayStartEpochMs — когда на сервере нажали PLAY/RESUME
    // startAtMs — с какой позиции начали (после seek и т.п.)
    private long serverPlayStartEpochMs = 0L;
    private long startAtMs = 0L;

    @Override
    public void onEnable() {
        getLogger().info("HelloPlugin включен!");

        getServer().getMessenger().registerOutgoingPluginChannel(this, KINO_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, KINO_CHANNEL, this);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KINO_CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, KINO_CHANNEL);

        getLogger().info("HelloPlugin выключен!");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!KINO_CHANNEL.equals(channel)) return;

        String text = new String(message, StandardCharsets.UTF_8);
        getLogger().info("[KINO <- " + player.getName() + "] " + text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /hello
        if (command.getName().equalsIgnoreCase("hello")) {
            sender.sendMessage("Привет! Плагин работает.");
            return true;
        }

        // /info
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

        // /kino
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

                    // При входе отправляем текущее состояние
                    sendFullStateToPlayer(player);
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
                        player.sendMessage("§cИспользование: §f/kino play <url>");
                        return true;
                    }

                    currentUrl = args[1];
                    playing = true;

                    // Стартуем с 0 (можно потом менять)
                    startAtMs = 0L;
                    serverPlayStartEpochMs = System.currentTimeMillis();

                    broadcastToViewers("§6[Кино] §aPLAY: §f" + currentUrl);

                    long now = System.currentTimeMillis();
                    broadcastPacketToViewers("PLAY|" + currentUrl + "|" + now + "|" + startAtMs);
                    return true;
                }

                case "pause" -> {
                    if (!mustBeViewer(player)) return true;
                    if (!playing || currentUrl == null) {
                        player.sendMessage("§eСеанс не запущен.");
                        return true;
                    }

                    long now = System.currentTimeMillis();
                    long pos = getCurrentPositionMs(now);

                    // фиксируем, что теперь не играет, но позиция сохранена
                    playing = false;
                    startAtMs = pos;

                    broadcastToViewers("§6[Кино] §ePAUSE §7(" + pos + " ms)");
                    broadcastPacketToViewers("PAUSE|" + now + "|" + pos);
                    return true;
                }

                case "resume" -> {
                    if (!mustBeViewer(player)) return true;
                    if (currentUrl == null) {
                        player.sendMessage("§eНет выбранного видео. Используй /kino play <url>");
                        return true;
                    }
                    if (playing) {
                        player.sendMessage("§eУже играет.");
                        return true;
                    }

                    long now = System.currentTimeMillis();
                    serverPlayStartEpochMs = now;
                    playing = true;

                    broadcastToViewers("§6[Кино] §aRESUME §7(from " + startAtMs + " ms)");
                    broadcastPacketToViewers("PLAY|" + currentUrl + "|" + now + "|" + startAtMs);
                    return true;
                }

                case "seek" -> {
                    if (!mustBeViewer(player)) return true;
                    if (currentUrl == null) {
                        player.sendMessage("§eНет выбранного видео. Используй /kino play <url>");
                        return true;
                    }
                    if (args.length < 2) {
                        player.sendMessage("§cИспользование: §f/kino seek <секунды>");
                        return true;
                    }

                    long seconds;
                    try {
                        seconds = Long.parseLong(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cЭто не число: §f" + args[1]);
                        return true;
                    }

                    long posMs = Math.max(0L, seconds * 1000L);
                    long now = System.currentTimeMillis();

                    // выставляем новую позицию
                    startAtMs = posMs;
                    if (playing) {
                        // если играло — “перезапускаем” отсчёт времени
                        serverPlayStartEpochMs = now;
                    }

                    broadcastToViewers("§6[Кино] §bSEEK §7(to " + posMs + " ms)");
                    broadcastPacketToViewers("SEEK|" + now + "|" + posMs);

                    // Если сейчас playing, можно сразу продублировать PLAY, чтобы клиенты точно перескочили
                    if (playing) {
                        broadcastPacketToViewers("PLAY|" + currentUrl + "|" + now + "|" + startAtMs);
                    }
                    return true;
                }

                case "stop" -> {
                    if (!mustBeViewer(player)) return true;
                    if (currentUrl == null) {
                        player.sendMessage("§eСеанс не запущен.");
                        return true;
                    }

                    long now = System.currentTimeMillis();

                    broadcastToViewers("§6[Кино] §cSTOP");
                    broadcastPacketToViewers("STOP|" + now);

                    currentUrl = null;
                    playing = false;
                    serverPlayStartEpochMs = 0L;
                    startAtMs = 0L;
                    return true;
                }

                case "status" -> {
                    long now = System.currentTimeMillis();
                    long pos = getCurrentPositionMs(now);

                    player.sendMessage("§6--- Кинотеатр ---");
                    player.sendMessage("§eЗрителей: §f" + viewers.size());
                    player.sendMessage("§eURL: §f" + (currentUrl == null ? "-" : currentUrl));
                    player.sendMessage("§eСостояние: §f" + (playing ? "PLAY" : "PAUSE/STOP"));
                    player.sendMessage("§eПозиция: §f" + pos + " ms");
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

    private boolean mustBeViewer(Player player) {
        if (!viewers.contains(player.getUniqueId())) {
            player.sendMessage("§cСначала войди в кинозал: §f/kino join");
            return false;
        }
        return true;
    }

    private void sendKinoHelp(Player player) {
        player.sendMessage("§6--- /kino ---");
        player.sendMessage("§e/kino join §7- войти в зал");
        player.sendMessage("§e/kino leave §7- выйти из зала");
        player.sendMessage("§e/kino play <url> §7- запустить");
        player.sendMessage("§e/kino pause §7- пауза");
        player.sendMessage("§e/kino resume §7- продолжить");
        player.sendMessage("§e/kino seek <sec> §7- перемотка");
        player.sendMessage("§e/kino stop §7- стоп");
        player.sendMessage("§e/kino status §7- статус");
    }

    private void broadcastToViewers(String message) {
        for (UUID uuid : viewers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) p.sendMessage(message);
        }
    }

    private void sendToViewer(Player player, String payload) {
        player.sendPluginMessage(this, KINO_CHANNEL, payload.getBytes(StandardCharsets.UTF_8));
    }

    private void broadcastPacketToViewers(String payload) {
        for (UUID uuid : viewers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) sendToViewer(p, payload);
        }
    }

    private void sendFullStateToPlayer(Player player) {
        long now = System.currentTimeMillis();

        if (currentUrl == null) {
            // ничего не играет — можно ничего не слать или слать STOP
            sendToViewer(player, "STOP|" + now);
            return;
        }

        if (playing) {
            long pos = getCurrentPositionMs(now);
            // Для нового зрителя лучше послать PLAY с текущей позицией
            sendToViewer(player, "PLAY|" + currentUrl + "|" + now + "|" + pos);
        } else {
            // На паузе — посылаем PAUSE с сохранённой позицией
            sendToViewer(player, "PAUSE|" + now + "|" + startAtMs);
        }
    }

    private long getCurrentPositionMs(long nowEpochMs) {
        if (currentUrl == null) return 0L;
        if (!playing) return startAtMs;

        long elapsed = nowEpochMs - serverPlayStartEpochMs;
        if (elapsed < 0) elapsed = 0;
        return startAtMs + elapsed;
    }
}
