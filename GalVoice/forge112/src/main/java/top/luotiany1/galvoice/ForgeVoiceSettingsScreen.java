package top.luotiany1.galvoice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.client.config.GuiSlider;
import org.lwjgl.input.Keyboard;

final class ForgeVoiceSettingsScreen extends GuiScreen {
    private static final int INPUT = 0;
    private static final int OUTPUT = 1;
    private static final int MIC_GAIN = 2;
    private static final int VOLUME = 3;
    private static final int RANGE = 4;
    private static final int DONE = 5;
    private static final boolean CHINESE_WINDOWS_JAVA8 =
            "1.8".equals(System.getProperty("java.specification.version"))
                    && System.getProperty("os.name", "").startsWith("Windows")
                    && "GBK".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"));

    private final VoiceSettings settings;
    private final VoiceAudio audio;
    private final GuiScreen parent;
    private final BooleanSupplier canChangeRange;
    private int top;
    private GuiButton range;
    private boolean inputFailed;
    private List<SoundDevice> inputs;
    private List<SoundDevice> outputs;

    ForgeVoiceSettingsScreen(VoiceSettings settings, VoiceAudio audio, GuiScreen parent,
                             BooleanSupplier canChangeRange) {
        this.settings = settings;
        this.audio = audio;
        this.parent = parent;
        this.canChangeRange = canChangeRange;
        inputFailed = audio.inputFailed();
    }

    @Override
    public void initGui() {
        top = Math.max(16, (height - 154) / 2);
        int size = Math.min(300, width - 24);
        int x = (width - size) / 2;
        inputs = SoundDevice.list(true);
        outputs = SoundDevice.list(false);
        addDevice(INPUT, x, top, size);
        addDevice(OUTPUT, x, top + 22, size);
        addSlider(MIC_GAIN, x, top + 44, size, "galvoice.mic_gain", settings.get().micGain(), gain -> {
            VoiceSettings.Snapshot old = settings.get();
            settings.update(new VoiceSettings.Snapshot(old.input(), old.output(), gain, old.volume()));
        });
        addSlider(VOLUME, x, top + 66, size, "galvoice.output_volume", settings.get().volume(), volume -> {
            VoiceSettings.Snapshot old = settings.get();
            settings.update(new VoiceSettings.Snapshot(old.input(), old.output(), old.micGain(), volume));
        });
        range = addButton(new GuiButton(RANGE, x, top + 88, size, 20, rangeLabel()));
        addButton(new GuiButton(DONE, x, top + 122, size, 20, I18n.format("gui.done")));
        updateScreen();
    }

    private void addSlider(int id, int x, int y, int width, String key, double value, DoubleConsumer set) {
        GuiSlider slider = new GuiSlider(id, x, y, width, 20, "", "", 0, 200, value * 100, false, true, moved -> {
            moved.displayString = I18n.format(key, moved.getValueInt());
            set.accept(moved.getValueInt() / 100.0);
        });
        slider.displayString = I18n.format(key, slider.getValueInt());
        addButton(slider);
    }

    private void addDevice(int id, int x, int y, int width) {
        GuiButton button = addButton(new GuiButton(id, x, y, width, 20, ""));
        updateDevice(button);
    }

    private void updateDevice(GuiButton button) {
        boolean input = button.id == INPUT;
        List<SoundDevice> devices = input ? inputs : outputs;
        String selected = input ? settings.get().input() : settings.get().output();
        int index = findDevice(devices, selected);
        String label = index < 0 ? I18n.format("galvoice.device.missing")
                : index == 0 ? I18n.format("galvoice.device.default") : deviceName(devices.get(index));
        String text = I18n.format(input ? "galvoice.device.input" : "galvoice.device.output", label);
        button.displayString = fontRenderer.trimStringToWidth(text, button.width - 10);
        button.enabled = devices.size() > 1 || index < 0;
    }

    private static String deviceName(SoundDevice device) {
        String name = device.label();
        if (!CHINESE_WINDOWS_JAVA8 || !device.id().contains("|Direct Audio Device: DirectSound ")
                || name.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                || name.chars().filter(c -> c >= 128 && c <= 255).count() < 2) {
            return name;
        }
        // Java 8's DirectSound provider can pass GBK names to JNI as UTF-8.
        // Recover the original bytes for display, keeping the device ID as reported by Java Sound.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(name.length());
        name.codePoints().forEach(c -> {
            if (c <= 255) {
                bytes.write(c);
            } else {
                byte[] encoded = new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        });
        try {
            String decoded = Charset.forName("GBK").newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            if (decoded.chars().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count() >= 2) {
                return decoded;
            }
        } catch (CharacterCodingException ignored) {
            // Preserve the original label if it was not an intact GBK string.
        }
        return name;
    }

    private static int findDevice(List<SoundDevice> devices, String id) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private String rangeLabel() {
        return I18n.format(settings.worldListening() ? "galvoice.listen.world" : "galvoice.listen.nearby");
    }

    @Override
    public void updateScreen() {
        range.visible = canChangeRange.getAsBoolean();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(parent);
        } else if (button.id == RANGE && canChangeRange.getAsBoolean()) {
            settings.toggleWorldListening();
            button.displayString = rangeLabel();
        } else if (button.id == INPUT || button.id == OUTPUT) {
            VoiceSettings.Snapshot old = settings.get();
            boolean input = button.id == INPUT;
            List<SoundDevice> devices = input ? inputs : outputs;
            int next = (findDevice(devices, input ? old.input() : old.output()) + 1) % devices.size();
            String device = devices.get(next).id();
            settings.update(input ? new VoiceSettings.Snapshot(device, old.output(), old.micGain(), old.volume())
                    : new VoiceSettings.Snapshot(old.input(), device, old.micGain(), old.volume()));
            if (input) {
                inputFailed = false;
            }
            updateDevice(button);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(fontRenderer, I18n.format("galvoice.settings"), width / 2, top - 13, 0xffffff);
        String error = inputFailed ? "galvoice.error.input" : audio.outputFailed() ? "galvoice.error.output" : null;
        if (error != null) {
            drawCenteredString(fontRenderer, I18n.format(error), width / 2, top + 111, 0xffa991);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        if (!settings.save() && mc.player != null) {
            mc.player.sendMessage(new TextComponentTranslation("galvoice.error.save"));
        }
    }
}
