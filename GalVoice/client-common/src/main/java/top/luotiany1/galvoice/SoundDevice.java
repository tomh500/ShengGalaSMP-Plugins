package top.luotiany1.galvoice;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

final class SoundDevice {
    private final String id;
    private final String label;
    private final Mixer.Info mixer;

    private SoundDevice(String id, String label, Mixer.Info mixer) {
        this.id = id;
        this.label = label;
        this.mixer = mixer;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    private static final int[] RATES = {VoicePacket.RATE, 48000, 44100};

    static List<SoundDevice> list(boolean input) {
        List<SoundDevice> options = new ArrayList<>();
        options.add(new SoundDevice("", "", null));
        DataLine.Info info = new DataLine.Info(input ? TargetDataLine.class : SourceDataLine.class, null);
        for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
            try {
                if (!AudioSystem.getMixer(mixer).isLineSupported(info)) {
                    continue;
                }
                String key = mixer.getName() + "|" + mixer.getVendor() + "|" + mixer.getDescription();
                int duplicate = 1;
                for (SoundDevice entry : options) {
                    if (entry.id.startsWith(key + "|")) {
                        duplicate++;
                    }
                }
                String label = mixer.getName() + (duplicate == 1 ? "" : " (" + duplicate + ")");
                options.add(new SoundDevice(key + "|" + duplicate, label, mixer));
            } catch (IllegalArgumentException ignored) {
                // A device may have been unplugged during enumeration.
            }
        }
        return options;
    }

    static DataLine open(String id, boolean input) throws LineUnavailableException {
        Mixer.Info requested = null;
        if (!id.isEmpty()) {
            requested = list(input).stream().filter(device -> device.id.equals(id))
                    .findFirst().orElseThrow(() -> new LineUnavailableException("Selected device is unavailable")).mixer;
        }
        Exception last = null;
        for (int rate : RATES) {
            for (int channels = 1; channels <= 2; channels++) {
                AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
                DataLine.Info info = new DataLine.Info(input ? TargetDataLine.class : SourceDataLine.class, format);
                DataLine line = null;
                try {
                    line = (DataLine) (requested == null ? AudioSystem.getLine(info)
                            : AudioSystem.getMixer(requested).getLine(info));
                    int bufferBytes = frameBytes(format) * 3;
                    if (line instanceof TargetDataLine) {
                        ((TargetDataLine) line).open(format, bufferBytes);
                    } else {
                        ((SourceDataLine) line).open(format, bufferBytes);
                    }
                    line.start();
                    return line;
                } catch (LineUnavailableException | RuntimeException failure) {
                    last = failure;
                    if (line != null) {
                        line.close();
                    }
                }
            }
        }
        LineUnavailableException failure = new LineUnavailableException("No supported 16-bit PCM format for the selected device");
        failure.initCause(last);
        throw failure;
    }

    private static int samplesPerFrame(AudioFormat format) {
        return (int) (format.getSampleRate() * VoicePacket.MILLIS / 1000);
    }

    static int frameBytes(AudioFormat format) {
        return samplesPerFrame(format) * format.getFrameSize();
    }

    static short[] capturePcm(byte[] bytes, AudioFormat format, double gain) {
        short[] pcm = new short[VoicePacket.SAMPLES];
        int frames = samplesPerFrame(format);
        int stride = format.getFrameSize();
        for (int i = 0; i < pcm.length; i++) {
            int start = i * frames / pcm.length;
            int end = (i + 1) * frames / pcm.length;
            int sum = 0;
            for (int j = start * stride; j < end * stride; j += 2) {
                sum += (short) ((bytes[j] & 255) | (bytes[j + 1] << 8));
            }
            pcm[i] = ImaAdpcm.clamp((int) (sum * gain / ((end - start) * format.getChannels())));
        }
        return pcm;
    }

    static byte[] playbackPcm(int[] mix, AudioFormat format, double gain) {
        byte[] bytes = new byte[frameBytes(format)];
        int frames = samplesPerFrame(format);
        int out = 0;
        for (int i = 0; i < frames; i++) {
            double offset = (double) i * mix.length / frames;
            int sample = (int) offset;
            int next = Math.min(sample + 1, mix.length - 1);
            double amplitude = mix[sample] + (mix[next] - mix[sample]) * (offset - sample);
            short value = ImaAdpcm.clamp((int) (amplitude * gain));
            for (int channel = 0; channel < format.getChannels(); channel++) {
                bytes[out++] = (byte) value;
                bytes[out++] = (byte) (value >> 8);
            }
        }
        return bytes;
    }
}
