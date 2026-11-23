package top.luotiany1.galvoice;

import java.util.List;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class VoiceSettingsScreen extends Screen {
    private final VoiceSettings settings;
    private final VoiceAudio audio;
    private final Screen parent;
    private final boolean canChangeRange;
    private int top;
    private boolean inputFailed;

    VoiceSettingsScreen(VoiceSettings settings, VoiceAudio audio, Screen parent, boolean canChangeRange) {
        super(Component.translatable("galvoice.settings"));
        this.settings = settings;
        this.audio = audio;
        this.parent = parent;
        this.canChangeRange = canChangeRange;
        inputFailed = audio.inputFailed();
    }

    @Override
    protected void init() {
        int rangeRow = canChangeRange ? 24 : 0;
        top = Math.max(22, (height - 132 - rangeRow) / 2);
        int size = Math.min(300, width - 24);
        int x = (width - size) / 2;
        addDevice(x, top, size, true);
        addDevice(x, top + 24, size, false);
        addRenderableWidget(new GainSlider(x, top + 48, size, "galvoice.mic_gain", settings.get().micGain(), gain -> {
            var old = settings.get();
            settings.update(new VoiceSettings.Snapshot(old.input(), old.output(), gain, old.volume()));
        }));
        addRenderableWidget(new GainSlider(x, top + 72, size, "galvoice.output_volume", settings.get().volume(), gain -> {
            var old = settings.get();
            settings.update(new VoiceSettings.Snapshot(old.input(), old.output(), old.micGain(), gain));
        }));
        if (canChangeRange) {
            addRenderableWidget(Button.builder(rangeLabel(), button -> {
                settings.toggleWorldListening();
                button.setMessage(rangeLabel());
            }).bounds(x, top + 96, size, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(x, top + 108 + rangeRow, size, 20).build());
    }

    private Component rangeLabel() {
        return Component.translatable(settings.worldListening() ? "galvoice.listen.world" : "galvoice.listen.nearby");
    }

    private void addDevice(int x, int y, int width, boolean input) {
        List<SoundDevice> devices = SoundDevice.list(input);
        String selected = input ? settings.get().input() : settings.get().output();
        int initial = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).id().equals(selected)) {
                initial = i;
                break;
            }
        }
        Button button = Button.builder(deviceLabel(input, devices, initial), pressed -> {
            var old = settings.get();
            String deviceId = input ? old.input() : old.output();
            int next = 0;
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).id().equals(deviceId)) {
                    next = (i + 1) % devices.size();
                    break;
                }
            }
            String id = devices.get(next).id();
            settings.update(input ? new VoiceSettings.Snapshot(id, old.output(), old.micGain(), old.volume())
                    : new VoiceSettings.Snapshot(old.input(), id, old.micGain(), old.volume()));
            if (input) {
                inputFailed = false;
            }
            label(pressed, deviceLabel(input, devices, next));
            pressed.active = devices.size() > 1;
        }).bounds(x, y, width, 20).build();
        button.active = devices.size() > 1 || initial == -1;
        label(button, deviceLabel(input, devices, initial));
        addRenderableWidget(button);
    }

    private Component deviceLabel(boolean input, List<SoundDevice> devices, int index) {
        Component device;
        if (index == -1) {
            device = Component.translatable("galvoice.device.missing");
        } else if (index == 0) {
            device = Component.translatable("galvoice.device.default");
        } else {
            device = Component.literal(devices.get(index).label());
        }
        return Component.translatable(input ? "galvoice.device.input" : "galvoice.device.output", device);
    }

    private void label(Button button, Component text) {
        button.setTooltip(Tooltip.create(text));
        String full = text.getString();
        int available = button.getWidth() - 10;
        button.setMessage(font.width(full) > available
                ? Component.literal(font.plainSubstrByWidth(full, available - font.width("…")) + "…") : text);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, top - 19, 0xffffffff);
        String error = inputFailed ? "galvoice.error.input"
                : audio.outputFailed() ? "galvoice.error.output" : null;
        if (error != null) {
            graphics.centeredText(font, Component.translatable(error), width / 2,
                    top + (canChangeRange ? 120 : 96), 0xffff9d83);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (!settings.save() && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable("galvoice.error.save"));
        }
        minecraft.gui.setScreen(parent);
    }

    private static final class GainSlider extends AbstractSliderButton {
        private final String key;
        private final DoubleConsumer setter;

        GainSlider(int x, int y, int width, String key, double gain, DoubleConsumer setter) {
            super(x, y, width, 20, Component.empty(), gain / 2);
            this.key = key;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, Math.round(value * 200)));
        }

        @Override
        protected void applyValue() {
            setter.accept(value * 2);
        }
    }
}
