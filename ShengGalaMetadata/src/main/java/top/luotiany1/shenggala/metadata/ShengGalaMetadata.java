package top.luotiany1.shenggala.metadata;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.Locale;
import java.util.Set;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.TabCompleteResponseEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.packet.PluginMessage;

public final class ShengGalaMetadata extends Plugin implements Listener {
    static final String BRAND = "圣旮旯群组服";
    private static final String INSPECT_PERMISSION = "shenggala.metadata.inspect";
    private static final Set<String> INFO_COMMANDS = Set.of(
            "version", "ver", "about", "plugins", "pl", "bungee",
            "viaversion", "viaver", "vv", "protocol", "icanhasbukkit"
    );

    @Override
    public void onEnable() {
        getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(ProxyPingEvent event) {
        ServerPing response = event.getResponse();
        if (response == null) {
            return;
        }
        response.setDescriptionComponent(new TextComponent(BRAND));
        if (response.getVersion() != null) {
            response.getVersion().setName(BRAND);
        }
        if (response.getPlayers() != null) {
            response.getPlayers().setSample(null);
        }
    }

    @EventHandler
    public void onLogin(PostLoginEvent event) {
        if (event.getPlayer() instanceof UserConnection connection) {
            connection.getCh().getHandle().pipeline().addLast("shenggala-brand", new BrandHandler());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(ChatEvent event) {
        if (event.isCommand() && event.getSender() instanceof ProxiedPlayer player
                && !player.hasPermission(INSPECT_PERMISSION) && isMetadataCommand(event.getMessage())) {
            event.setCancelled(true);
            player.sendMessage(new TextComponent("未知命令。"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCompletion(TabCompleteResponseEvent event) {
        if (event.getReceiver() instanceof ProxiedPlayer player && !player.hasPermission(INSPECT_PERMISSION)) {
            event.getSuggestions().removeIf(ShengGalaMetadata::isMetadataCommand);
        }
    }

    static boolean isMetadataCommand(String input) {
        String token = input.stripLeading().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (token.startsWith("/")) {
            token = token.substring(1);
        }
        int namespace = token.indexOf(':');
        if (namespace >= 0) {
            token = token.substring(namespace + 1);
        }
        return INFO_COMMANDS.contains(token);
    }

    static final class BrandHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            Object packet = message instanceof PacketWrapper wrapper ? wrapper.packet : message;
            if (packet instanceof PluginMessage pluginMessage
                    && ("MC|Brand".equals(pluginMessage.getTag()) || "minecraft:brand".equals(pluginMessage.getTag()))) {
                ByteBuf payload = Unpooled.buffer();
                try {
                    DefinedPacket.writeString(BRAND, payload);
                    if (message instanceof PacketWrapper wrapper) {
                        wrapper.trySingleRelease();
                    }
                    message = new PluginMessage(pluginMessage.getTag(), DefinedPacket.toArray(payload),
                            pluginMessage.isAllowExtendedPacket());
                } finally {
                    payload.release();
                }
            }
            context.write(message, promise);
        }
    }
}
