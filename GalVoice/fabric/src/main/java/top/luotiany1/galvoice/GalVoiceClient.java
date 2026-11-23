package top.luotiany1.galvoice;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import top.luotiany1.mcqq.crossplay.FabricMasterGAL;
import top.luotiany1.mcqq.crossplay.GalApi;

public final class GalVoiceClient implements ClientModInitializer {
    private static final long SPEAKING = TimeUnit.MILLISECONDS.toNanos(800);
    private final LinkedHashMap<UUID, Talker> talkers = new LinkedHashMap<>();
    private KeyMapping pushToTalk;
    private KeyMapping settingsKey;
    private VoiceSettings settings;
    private VoiceAudio audio;
    private GalApi<Minecraft> gal;
    private boolean released;
    private boolean ready;
    private boolean openRequested;
    private Boolean sentWorldListening;
    private long lastUpload;

    private record Talker(String name, VoicePacket.Channel channel, long seen) {}
    private record Badge(UUID player, String name, Component status, int accent) {}

    @Override
    public void onInitializeClient() {
        var category = KeyMapping.Category.register(id("voice"));
        pushToTalk = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galvoice.push_to_talk",
                InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_4, category));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galvoice.settings",
                InputConstants.KEY_V, category));
        settings = new VoiceSettings(FabricLoader.getInstance().getConfigDir().resolve("galvoice.properties"));
        audio = new VoiceAudio(settings);
        gal = FabricMasterGAL.api();
        gal.onEvent(VoicePacket.AUDIO, this::incoming);
        gal.onReady(client -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> audio.close());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("galvoice").executes(command -> {
                    openRequested = true;
                    return 1;
                })));
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, id("speakers"), this::draw);
    }

    private void tick(Minecraft client) {
        boolean pressed = settingsKey.consumeClick();
        if (openRequested || (pressed && client.gui.screen() == null)) {
            openRequested = false;
            openSettings(client);
        }
        while (pushToTalk.consumeClick()) {
            // Held state, rather than queued clicks, controls capture.
        }
        boolean available = client.player != null && gal.canSendEvent(client, VoicePacket.UPLOAD);
        if (ready && !available) {
            reset();
        }
        ready = available;
        if (ready && gal.canSendEvent(client, VoicePacket.LISTEN_MODE)) {
            boolean worldListening = settings.worldListening();
            if ((sentWorldListening == null || sentWorldListening != worldListening)
                    && gal.sendEvent(client, VoicePacket.LISTEN_MODE, new byte[]{(byte) (worldListening ? 1 : 0)})) {
                sentWorldListening = worldListening;
            }
        }
        if (!pushToTalk.isDown()) {
            released = true;
        }
        boolean talking = ready && released && pushToTalk.isDown() && client.gui.screen() == null
                && client.isWindowActive() && settings.get().micGain() > 0;
        audio.setCapture(talking);
        if (!talking) {
            lastUpload = 0;
            if (pushToTalk.isDown()) {
                released = false;
            }
        } else {
            for (int sent = 0; sent < 3; sent++) {
                byte[] frame = audio.pollCapture();
                if (frame == null || !gal.sendEvent(client, VoicePacket.UPLOAD, frame)) {
                    break;
                }
                lastUpload = System.nanoTime();
            }
        }
        long now = System.nanoTime();
        talkers.values().removeIf(speaker -> now - speaker.seen > SPEAKING);
    }

    private void incoming(Minecraft client, byte[] payload) {
        if (client.player == null || !gal.canSendEvent(client, VoicePacket.UPLOAD)) {
            return;
        }
        VoicePacket frame = VoicePacket.decode(payload);
        if (frame == null || frame.speaker.equals(client.player.getUUID())) {
            return;
        }
        talkers.put(frame.speaker, new Talker(frame.name, frame.channel, System.nanoTime()));
        if (talkers.size() > 24) {
            talkers.pollFirstEntry();
        }
        if (settings.get().volume() > 0) {
            audio.receive(frame);
        }
    }

    private void openSettings(Minecraft client) {
        if (!(client.gui.screen() instanceof VoiceSettingsScreen)) {
            client.gui.setScreen(new VoiceSettingsScreen(settings, audio, client.gui.screen(),
                    gal.canSendEvent(client, VoicePacket.LISTEN_MODE)));
        }
    }

    private void reset() {
        ready = false;
        sentWorldListening = null;
        released = false;
        lastUpload = 0;
        talkers.clear();
        audio.reset();
    }

    private void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        // Vanilla anchors its bottom chat line 40 GUI pixels from the bottom.
        int y = graphics.guiHeight() - 38;
        long now = System.nanoTime();
        var badges = new ArrayList<Badge>();
        for (var entry : talkers.entrySet()) {
            Talker speaker = entry.getValue();
            if (now - speaker.seen <= SPEAKING) {
                String key = speaker.channel == VoicePacket.Channel.NEARBY && Boolean.TRUE.equals(sentWorldListening)
                        ? "galvoice.channel.world"
                        : "galvoice.channel." + speaker.channel.name().toLowerCase(Locale.ROOT);
                int accent = switch (speaker.channel) {
                    case NEARBY -> 0xff8fe6b7;
                    case TEAM -> 0xff8fcaff;
                    case SPECTATOR -> 0xffd3b3ff;
                };
                badges.add(new Badge(entry.getKey(), speaker.name, Component.translatable(key), accent));
            }
        }
        String selfStatus = null;
        int selfColor = 0xff8fe6b7;
        if (pushToTalk.isDown() && audio.inputFailed()) {
            selfStatus = "galvoice.error.input";
            selfColor = 0xffffa991;
        } else if (lastUpload != 0 && now - lastUpload < SPEAKING) {
            selfStatus = "galvoice.sending";
        }
        if (selfStatus != null) {
            badges.add(new Badge(client.player.getUUID(), client.player.getGameProfile().name(),
                    Component.translatable(selfStatus), selfColor));
        }
        if (badges.isEmpty()) {
            return;
        }

        int available = Math.max(1, graphics.guiWidth() - 12);
        int shown = Math.min(badges.size(), Math.min(3, Math.max(1, (available + 3) / 75)));
        if (shown < badges.size() && shown > 1 && shown * 75 + 26 > available) {
            shown--;
        }
        int hidden = badges.size() - shown;
        int reserve = hidden > 0 ? 26 : 0;
        int maxWidth = Math.max(28, (available - reserve - (shown - 1) * 3) / shown);
        int x = 5;
        for (int i = 0; i < shown; i++) {
            x += drawBadge(client, graphics, badges.get(i), x, y, maxWidth) + 3;
        }
        if (hidden > 0) {
            String label = "+" + hidden;
            graphics.fill(x, y, x + 23, y + 16, 0xd919222b);
            graphics.text(client.font, label, x + 4, y + 4, 0xffe3e9ee, false);
        }
        if (!talkers.isEmpty() && audio.outputFailed()) {
            Component error = Component.translatable("galvoice.error.output");
            String label = fit(client, error.getString(), available - 8);
            graphics.fill(5, y + 18, 13 + client.font.width(label), y + 29, 0xd919222b);
            graphics.text(client.font, label, 9, y + 19, 0xffffa991, false);
        }
    }

    private static int drawBadge(Minecraft client, GuiGraphicsExtractor graphics, Badge badge,
                                 int x, int y, int maxWidth) {
        String status = badge.status.getString();
        int naturalWidth = 24 + Math.max(client.font.width(badge.name), client.font.width(status));
        int width = Math.min(maxWidth, Math.max(59, naturalWidth));
        graphics.fill(x, y, x + width, y + 16, 0xe619222b);
        graphics.fill(x, y, x + 2, y + 16, badge.accent);

        var connection = client.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(badge.player);
        var skin = info == null ? DefaultPlayerSkin.get(badge.player) : info.getSkin();
        PlayerFaceExtractor.extractRenderState(graphics, skin, x + 4, y + 2, 12);
        graphics.text(client.font, fit(client, badge.name, width - 23), x + 19, y, 0xfff5f7f8, false);
        graphics.text(client.font, fit(client, status, width - 23), x + 19, y + 8, badge.accent, false);
        return width;
    }

    private static String fit(Minecraft client, String text, int width) {
        if (client.font.width(text) <= width) {
            return text;
        }
        return client.font.plainSubstrByWidth(text, Math.max(0, width - client.font.width("…"))) + "…";
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("galvoice", path);
    }
}
