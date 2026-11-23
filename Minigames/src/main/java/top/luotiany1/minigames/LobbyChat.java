package top.luotiany1.minigames;

import java.nio.charset.StandardCharsets;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

final class LobbyChat implements Listener {
    private static final String CHANNEL = "mcqq:bwlobby";
    private final MinigamesPlugin plugin;
    private final boolean relay;

    LobbyChat(MinigamesPlugin plugin) {
        this.plugin = plugin;
        relay = plugin.getConfig().getBoolean("qq-bridge.lobby-chat");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (relay) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL,
                    (channel, carrier, bytes) -> receive(bytes));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        // External /said implementations can dispatch a synchronous moderation event.
        if (!event.isAsynchronous()) return;
        String message = event.getMessage();
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!message.trim().isEmpty() && message.length() <= 2000) {
            Bukkit.getScheduler().runTask(plugin, () -> send(player, message));
        }
    }

    private void send(Player sender, String message) {
        if (!sender.isOnline()) return;
        GameRoom senderRoom = plugin.roomOf(sender);
        boolean match = senderRoom != null && senderRoom.inMatch(sender);
        boolean active = match && senderRoom.competitive(sender);
        String scope = match ? (active ? senderRoom.name : "观战 " + senderRoom.name) : "小游戏大厅";
        String text = ChatColor.GRAY + "[" + scope + "] " + ChatColor.WHITE + sender.getDisplayName()
                + ChatColor.GRAY + " » " + ChatColor.WHITE + message;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (match ? senderRoom.inMatch(player) && senderRoom.competitive(player) == active : !plugin.inMatch(player)) {
                player.sendMessage(text);
            }
        }
        if (!match && relay) {
            ByteArrayDataOutput packet = ByteStreams.newDataOutput();
            packet.writeUTF(message);
            sender.sendPluginMessage(plugin, CHANNEL, packet.toByteArray());
        }
    }

    private void receive(byte[] bytes) {
        try {
            BaseComponent[] message = ComponentSerializer.parse(new String(bytes, StandardCharsets.UTF_8));
            for (Player player : plugin.world().getPlayers()) {
                if (!plugin.inMatch(player)) player.spigot().sendMessage(message);
            }
        } catch (RuntimeException invalid) {
            plugin.getLogger().fine("Ignored invalid MCQQ lobby chat packet");
        }
    }
}
