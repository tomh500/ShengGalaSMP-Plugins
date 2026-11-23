package top.luotiany1.galvoice;

/** Independent mono IMA ADPCM blocks with a little-endian predictor and low nibble first. */
final class ImaAdpcm {
    private static final int[] STEP = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
        1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026,
        4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };
    private static final int[] INDEX_DELTA = {-1, -1, -1, -1, 2, 4, 6, 8};

    static byte[] encode(short[] pcm) {
        byte[] bytes = new byte[VoicePacket.AUDIO_BYTES];
        State state = new State(pcm[0], initialIndex(pcm));
        bytes[0] = (byte) pcm[0];
        bytes[1] = (byte) (pcm[0] >> 8);
        bytes[2] = (byte) state.index;
        for (int i = 1; i < VoicePacket.SAMPLES; i++) {
            int code = state.encode(pcm[i]);
            bytes[4 + (i - 1) / 2] |= (byte) (code << (((i - 1) & 1) * 4));
        }
        return bytes;
    }

    static short[] decode(byte[] bytes) {
        short first = (short) ((bytes[0] & 255) | (bytes[1] << 8));
        State state = new State(first, bytes[2] & 255);
        short[] pcm = new short[VoicePacket.SAMPLES];
        pcm[0] = first;
        for (int i = 1; i < VoicePacket.SAMPLES; i++) {
            int code = (bytes[4 + (i - 1) / 2] >> (((i - 1) & 1) * 4)) & 15;
            pcm[i] = state.decode(code);
        }
        return pcm;
    }

    private static int initialIndex(short[] pcm) {
        int change = 0;
        for (int i = 1; i <= 16; i++) {
            change += Math.abs(pcm[i] - pcm[i - 1]);
        }
        int index = 0;
        while (index < STEP.length - 1 && STEP[index] < change / 16) {
            index++;
        }
        return index;
    }

    static short clamp(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static final class State {
        int predictor;
        int index;

        State(int predictor, int index) {
            this.predictor = predictor;
            this.index = index;
        }

        int encode(short sample) {
            int delta = sample - predictor;
            int remaining = Math.abs(delta);
            int step = STEP[index];
            int code = delta < 0 ? 8 : 0;
            for (int bit = 4; bit > 0; bit >>= 1, step >>= 1) {
                if (remaining >= step) {
                    code |= bit;
                    remaining -= step;
                }
            }
            decode(code);
            return code;
        }

        short decode(int code) {
            int step = STEP[index];
            int delta = step >> 3;
            delta += (code & 4) == 0 ? 0 : step;
            delta += (code & 2) == 0 ? 0 : step >> 1;
            delta += (code & 1) == 0 ? 0 : step >> 2;
            predictor = clamp(predictor + ((code & 8) == 0 ? delta : -delta));
            index = Math.max(0, Math.min(88, index + INDEX_DELTA[code & 7]));
            return (short) predictor;
        }
    }

    private ImaAdpcm() {}
}
