package top.luotiany1.galvoice;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class VoicePacket {
    public static final String UPLOAD = "galvoice:upload_v1";
    public static final String AUDIO = "galvoice:audio_v1";
    public static final String LISTEN_MODE = "galvoice:listen_mode_v1";
    public static final int RATE = 16000;
    public static final int MILLIS = 40;
    public static final int SAMPLES = RATE * MILLIS / 1000;
    public static final int AUDIO_BYTES = 4 + SAMPLES / 2;
    private static final int HEADER = 19;
    private static final int MAX_NAME = 64;

    public enum Channel { NEARBY, TEAM, SPECTATOR }

    public final UUID speaker;
    public final String name;
    public final Channel channel;
    public final int gain;
    public final byte[] audio;

    private VoicePacket(UUID speaker, String name, Channel channel, int gain, byte[] audio) {
        this.speaker = speaker;
        this.name = name;
        this.channel = channel;
        this.gain = gain;
        this.audio = audio;
    }

    public static boolean validAudio(byte[] audio) {
        return audio.length == AUDIO_BYTES && (audio[2] & 255) <= 88 && audio[3] == 0
                && (audio[AUDIO_BYTES - 1] & 240) == 0;
    }

    public static byte[] relay(UUID speaker, byte[] name, Channel channel, int gain, byte[] audio) {
        if (name.length == 0 || name.length > MAX_NAME) {
            return null;
        }
        return ByteBuffer.allocate(HEADER + name.length + AUDIO_BYTES)
                .putLong(speaker.getMostSignificantBits()).putLong(speaker.getLeastSignificantBits())
                .put((byte) channel.ordinal()).put((byte) gain).put((byte) name.length)
                .put(name).put(audio).array();
    }

    public static byte[] nameBytes(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    public static VoicePacket decode(byte[] bytes) {
        if (bytes.length <= HEADER + AUDIO_BYTES || bytes.length > HEADER + MAX_NAME + AUDIO_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        UUID speaker = new UUID(buffer.getLong(), buffer.getLong());
        int channel = buffer.get() & 255;
        int gain = buffer.get() & 255;
        int nameLength = buffer.get() & 255;
        if (channel >= Channel.values().length || gain == 0
                || nameLength != buffer.remaining() - AUDIO_BYTES) {
            return null;
        }
        byte[] name = new byte[nameLength];
        byte[] audio = new byte[AUDIO_BYTES];
        buffer.get(name).get(audio);
        return validAudio(audio) ? new VoicePacket(speaker, new String(name, StandardCharsets.UTF_8),
                Channel.values()[channel], gain, audio) : null;
    }
}
