package top.luotiany1.galvoice;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import top.luotiany1.mcqq.crossplay.ForgeMaster;
import top.luotiany1.mcqq.crossplay.GalApi;

@Mod(modid = "galvoice", name = "GalVoice", clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*",
        dependencies = "required-after:forgemaster@[0.1.0,)")
public final class ForgeVoiceClient {
    private static final long SPEAKING = TimeUnit.MILLISECONDS.toNanos(800);
    private static final String CATEGORY = "key.category.galvoice.voice";
    private final LinkedHashMap<UUID, Talker> talkers = new LinkedHashMap<>();
    private final KeyBinding pushToTalk = new KeyBinding("key.galvoice.push_to_talk", -97, CATEGORY);
    private final KeyBinding settingsKey = new KeyBinding("key.galvoice.settings", Keyboard.KEY_V, CATEGORY);
    private VoiceSettings settings;
    private VoiceAudio audio;
    private GalApi<Minecraft> gal;
    private boolean released;
    private boolean ready;
    private boolean openRequested;
    private Boolean sentWorldListening;
    private long lastUpload;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Path config = event.getModConfigurationDirectory().toPath();
        settings = new VoiceSettings(config.resolve("galvoice.properties"));
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        audio = new VoiceAudio(settings);
        gal = ForgeMaster.api();
        gal.onEvent(VoicePacket.AUDIO, this::incoming);
        gal.onReady(client -> reset());
        ForgeMaster.onSessionClosed(client -> reset());
        ClientRegistry.registerKeyBinding(pushToTalk);
        ClientRegistry.registerKeyBinding(settingsKey);
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new CommandBase() {
            @Override
            public String getName() {
                return "galvoice";
            }

            @Override
            public String getUsage(ICommandSender sender) {
                return "/galvoice";
            }

            @Override
            public int getRequiredPermissionLevel() {
                return 0;
            }

            @Override
            public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
                openRequested = true;
            }
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getMinecraft();
        boolean openKey = settingsKey.isPressed();
        if (openRequested || (openKey && client.currentScreen == null)) {
            openRequested = false;
            if (!(client.currentScreen instanceof ForgeVoiceSettingsScreen)) {
                client.displayGuiScreen(new ForgeVoiceSettingsScreen(settings, audio, client.currentScreen,
                        () -> canUseVoice(client) && gal.canSendEvent(client, VoicePacket.LISTEN_MODE)));
            }
        }
        while (pushToTalk.isPressed()) {
            // Drain queued clicks. Only the current held state controls capture.
        }

        boolean available = canUseVoice(client);
        if (ready && !available) {
            reset();
        }
        ready = available;
        if (ready && gal.canSendEvent(client, VoicePacket.LISTEN_MODE)) {
            boolean world = settings.worldListening();
            if ((sentWorldListening == null || sentWorldListening != world)
                    && gal.sendEvent(client, VoicePacket.LISTEN_MODE, new byte[]{(byte) (world ? 1 : 0)})) {
                sentWorldListening = world;
            }
        }

        // Forge clears KeyBinding states while a GUI is open. Require a physical release
        // after a reset or focus loss so a held button cannot open the next microphone session.
        boolean focused = Display.isActive() && client.inGameHasFocus && client.currentScreen == null;
        boolean held = focused && GameSettings.isKeyDown(pushToTalk);
        if (!ready || !focused) {
            released = false;
        } else if (!held) {
            released = true;
        }
        boolean talking = ready && released && held && pushToTalk.isKeyDown() && settings.get().micGain() > 0;
        audio.setCapture(talking);
        if (talking) {
            for (int sent = 0; sent < 3; sent++) {
                byte[] frame = audio.pollCapture();
                if (frame == null || !gal.sendEvent(client, VoicePacket.UPLOAD, frame)) {
                    break;
                }
                lastUpload = System.nanoTime();
            }
        } else {
            lastUpload = 0;
        }

        long now = System.nanoTime();
        talkers.values().removeIf(speaker -> now - speaker.seen > SPEAKING);
    }

    private boolean canUseVoice(Minecraft client) {
        return client.player != null && gal.canSendEvent(client, VoicePacket.UPLOAD);
    }

    private void incoming(Minecraft client, byte[] payload) {
        if (!canUseVoice(client)) {
            return;
        }
        VoicePacket frame = VoicePacket.decode(payload);
        if (frame == null || frame.speaker.equals(client.player.getUniqueID())) {
            return;
        }
        talkers.put(frame.speaker, new Talker(frame));
        if (talkers.size() > 24) {
            talkers.remove(talkers.keySet().iterator().next());
        }
        if (settings.get().volume() > 0) {
            audio.receive(frame);
        }
    }

    private void reset() {
        ready = false;
        released = false;
        sentWorldListening = null;
        lastUpload = 0;
        talkers.clear();
        audio.reset();
    }

    @SubscribeEvent
    public void draw(RenderGameOverlayEvent.Post event) {
        Minecraft client = Minecraft.getMinecraft();
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || client.player == null
                || client.gameSettings.hideGUI || client.currentScreen != null) {
            return;
        }
        int x = 5;
        int width = event.getResolution().getScaledWidth();
        int y = event.getResolution().getScaledHeight() - 34;
        int count = 0;
        for (Talker talker : talkers.values()) {
            if (count == 3 || width - x < 40) {
                break;
            }
            String channel = talker.channel == VoicePacket.Channel.NEARBY && Boolean.TRUE.equals(sentWorldListening)
                    ? "world" : talker.channel.name().toLowerCase(Locale.ROOT);
            String label = talker.name + " - " + I18n.format("galvoice.channel." + channel);
            x += drawLabel(client, x, y, width - x - 5, label, 0x8fe6b7) + 3;
            count++;
        }
        if (talkers.size() > count && width - x >= 30) {
            drawLabel(client, x, y, width - x - 5, "+" + (talkers.size() - count), 0xffffff);
        }
        String status = null;
        boolean error = true;
        if (ready && released && pushToTalk.isKeyDown() && audio.inputFailed()) {
            status = "galvoice.error.input";
        } else if (!talkers.isEmpty() && audio.outputFailed()) {
            status = "galvoice.error.output";
        } else if (lastUpload != 0 && System.nanoTime() - lastUpload < SPEAKING) {
            status = "galvoice.sending";
            error = false;
        }
        if (status != null) {
            drawLabel(client, 5, y + 13, width - 10, I18n.format(status), error ? 0xffa991 : 0x8fe6b7);
        }
    }

    private static int drawLabel(Minecraft client, int x, int y, int available, String label, int color) {
        String fitted = client.fontRenderer.trimStringToWidth(label, Math.max(0, Math.min(120, available - 8)));
        int width = client.fontRenderer.getStringWidth(fitted) + 8;
        Gui.drawRect(x, y, x + width, y + 12, 0xe619222b);
        client.fontRenderer.drawStringWithShadow(fitted, x + 4, y + 2, color);
        return width;
    }

    private static final class Talker {
        final String name;
        final VoicePacket.Channel channel;
        final long seen = System.nanoTime();

        Talker(VoicePacket frame) {
            name = frame.name;
            channel = frame.channel;
        }
    }
}
