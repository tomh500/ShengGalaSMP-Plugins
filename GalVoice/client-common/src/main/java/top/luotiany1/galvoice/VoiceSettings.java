package top.luotiany1.galvoice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

final class VoiceSettings {
    private static final Logger LOG = Logger.getLogger("GalVoice");
    private final Path path;
    private volatile Snapshot current;
    private boolean worldListening;

    static final class Snapshot {
        private final String input;
        private final String output;
        private final double micGain;
        private final double volume;

        Snapshot(String input, String output, double micGain, double volume) {
            this.input = input;
            this.output = output;
            this.micGain = micGain;
            this.volume = volume;
        }

        String input() {
            return input;
        }

        String output() {
            return output;
        }

        double micGain() {
            return micGain;
        }

        double volume() {
            return volume;
        }
    }

    VoiceSettings(Path path) {
        this.path = path;
        Properties props = new Properties();
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                props.load(input);
            } catch (IOException | IllegalArgumentException failure) {
                LOG.log(Level.WARNING, "Unable to read GalVoice settings", failure);
            }
        }
        current = new Snapshot(props.getProperty("input", ""), props.getProperty("output", ""),
                gain(props, "micGain"), gain(props, "volume"));
        worldListening = Boolean.parseBoolean(props.getProperty("worldListening", "false"));
    }

    private static double gain(Properties props, String key) {
        try {
            double value = Double.parseDouble(props.getProperty(key, "1.0"));
            return Double.isFinite(value) ? Math.max(0, Math.min(2, value)) : 1;
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    Snapshot get() {
        return current;
    }

    void update(Snapshot values) {
        current = values;
    }

    boolean worldListening() {
        return worldListening;
    }

    void toggleWorldListening() {
        worldListening = !worldListening;
    }

    boolean save() {
        Snapshot values = current;
        Properties props = new Properties();
        props.setProperty("input", values.input());
        props.setProperty("output", values.output());
        props.setProperty("micGain", Double.toString(values.micGain()));
        props.setProperty("volume", Double.toString(values.volume()));
        props.setProperty("worldListening", Boolean.toString(worldListening));
        try {
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "galvoice-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temp)) {
                    props.store(output, "GalVoice");
                }
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "Unable to save GalVoice settings", failure);
            return false;
        }
    }
}
