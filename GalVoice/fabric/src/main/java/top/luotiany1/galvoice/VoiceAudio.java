package top.luotiany1.galvoice;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class VoiceAudio {
    private static final Logger LOG = LoggerFactory.getLogger("GalVoice");
    private static final long FRAME = TimeUnit.MILLISECONDS.toNanos(VoicePacket.MILLIS);
    private static final long MAX_AGE = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long OUTPUT_IDLE = TimeUnit.SECONDS.toNanos(2);
    private final VoiceSettings settings;
    private final ArrayBlockingQueue<Captured> captured = new ArrayBlockingQueue<>(4);
    private final ArrayBlockingQueue<Received> received = new ArrayBlockingQueue<>(64);
    private final Thread captureThread = worker("GalVoice microphone", this::captureLoop);
    private final Thread playbackThread = worker("GalVoice playback", this::playbackLoop);
    private volatile Capture request;
    private volatile Capture failedCapture;
    private volatile long generation;
    private volatile boolean outputFailed;
    private volatile boolean closed;

    private record Capture(String device) {}
    private record Captured(Capture request, long time, byte[] bytes) {}
    private record Received(long generation, long time, VoicePacket packet) {}

    VoiceAudio(VoiceSettings settings) {
        this.settings = settings;
        captureThread.start();
        playbackThread.start();
    }

    private static Thread worker(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    void setCapture(boolean talking) {
        if (talking && request == null) {
            failedCapture = null;
            request = new Capture(settings.get().input());
        } else if (!talking && request != null) {
            request = null;
            captured.clear();
        }
    }

    byte[] pollCapture() {
        Captured frame;
        while ((frame = captured.poll()) != null) {
            if (frame.request == request && System.nanoTime() - frame.time < MAX_AGE) {
                return frame.bytes;
            }
        }
        return null;
    }

    void receive(VoicePacket packet) {
        enqueue(received, new Received(generation, System.nanoTime(), packet));
    }

    boolean inputFailed() {
        var failure = failedCapture;
        return failure != null && failure.device.equals(settings.get().input());
    }

    boolean outputFailed() {
        return outputFailed;
    }

    void reset() {
        setCapture(false);
        generation++;
        received.clear();
        outputFailed = false;
    }

    void close() {
        reset();
        closed = true;
        captureThread.interrupt();
        playbackThread.interrupt();
    }

    private static <T> void enqueue(ArrayBlockingQueue<T> queue, T item) {
        if (!queue.offer(item)) {
            queue.poll();
            queue.offer(item);
        }
    }

    private void captureLoop() {
        try {
            while (!closed) {
                Capture current = request;
                if (current == null || current == failedCapture) {
                    Thread.sleep(10);
                    continue;
                }
                capture(current);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void capture(Capture current) throws InterruptedException {
        try (var input = (TargetDataLine) SoundDevice.open(current.device, true)) {
            var format = input.getFormat();
            byte[] pcm = new byte[SoundDevice.frameBytes(format)];
            long lastRead = System.nanoTime();
            while (!closed && current == request) {
                if (!input.isOpen() || System.nanoTime() - lastRead >= OUTPUT_IDLE) {
                    throw new LineUnavailableException("Microphone stopped supplying samples");
                }
                if (input.available() < pcm.length) {
                    Thread.sleep(5);
                    continue;
                }
                int length = input.read(pcm, 0, pcm.length);
                if (length == pcm.length && current == request) {
                    lastRead = System.nanoTime();
                    byte[] bytes = ImaAdpcm.encode(SoundDevice.capturePcm(pcm, format, settings.get().micGain()));
                    enqueue(captured, new Captured(current, System.nanoTime(), bytes));
                }
            }
        } catch (LineUnavailableException | RuntimeException failure) {
            failedCapture = current;
            LOG.debug("Could not capture voice", failure);
        }
    }

    private void playbackLoop() {
        var mixer = new Playback();
        try {
            long next = System.nanoTime();
            while (!closed) {
                long now = System.nanoTime();
                if (next - now > 0) {
                    TimeUnit.NANOSECONDS.sleep(next - now);
                    now = System.nanoTime();
                }
                next = now - next > FRAME ? now + FRAME : next + FRAME;
                mixer.tick(now);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            mixer.closeOutput();
        }
    }

    private final class Playback {
        private final Map<UUID, Stream> streams = new HashMap<>();
        private SourceDataLine output;
        private String device = "";
        private long activeGeneration = -1;
        private long lastAudio;
        private long retryAfter;

        void tick(long now) {
            VoiceSettings.Snapshot preferences = settings.get();
            long session = generation;
            if (session != activeGeneration || !device.equals(preferences.output()) || preferences.volume() == 0) {
                closeOutput();
                streams.clear();
                retryAfter = now;
                activeGeneration = session;
                device = preferences.output();
                outputFailed = false;
            }

            Received frame;
            while ((frame = received.poll()) != null) {
                if (frame.generation == session && now - frame.time < MAX_AGE && preferences.volume() > 0) {
                    addFrame(frame, now);
                }
            }
            int[] mix = new int[VoicePacket.SAMPLES];
            boolean hasAudio = mixInto(mix, now);
            if (session != generation || closed) {
                return;
            }
            if (hasAudio) {
                lastAudio = now;
            } else if (now - lastAudio >= OUTPUT_IDLE) {
                closeOutput();
            }
            if (hasAudio && output == null && now >= retryAfter) {
                try {
                    output = (SourceDataLine) SoundDevice.open(device, false);
                    outputFailed = false;
                } catch (LineUnavailableException | RuntimeException failure) {
                    failed(now, failure);
                }
            }
            if (output != null && session == generation && !closed) {
                write(mix, preferences.volume(), now);
            }
        }

        private void addFrame(Received frame, long now) {
            UUID speaker = frame.packet.speaker;
            Stream stream = streams.get(speaker);
            if (stream == null) {
                if (streams.size() >= 16) {
                    return;
                }
                stream = new Stream(now + FRAME);
                streams.put(speaker, stream);
            }
            stream.last = now;
            if (stream.queue.size() >= 4) {
                stream.queue.removeFirst();
            }
            stream.queue.addLast(frame);
        }

        private boolean mixInto(int[] mix, long now) {
            boolean hasAudio = false;
            var iterator = streams.values().iterator();
            while (iterator.hasNext()) {
                Stream stream = iterator.next();
                if (now - stream.last > MAX_AGE) {
                    iterator.remove();
                    continue;
                }
                if (now < stream.start || stream.queue.isEmpty()) {
                    continue;
                }
                Received frame;
                do {
                    frame = stream.queue.pollFirst();
                } while (frame != null && now - frame.time >= MAX_AGE);
                if (frame != null) {
                    short[] pcm = ImaAdpcm.decode(frame.packet.audio);
                    for (int i = 0; i < pcm.length; i++) {
                        mix[i] += pcm[i] * frame.packet.gain / 255;
                    }
                    hasAudio = true;
                }
            }
            return hasAudio;
        }

        private void write(int[] mix, double volume, long now) {
            try {
                if (!output.isOpen()) {
                    throw new LineUnavailableException("Playback device stopped");
                }
                if (output.available() >= SoundDevice.frameBytes(output.getFormat())) {
                    byte[] bytes = SoundDevice.playbackPcm(mix, output.getFormat(), volume);
                    output.write(bytes, 0, bytes.length);
                }
            } catch (LineUnavailableException | RuntimeException failure) {
                failed(now, failure);
            }
        }

        private void failed(long now, Exception failure) {
            closeOutput();
            outputFailed = true;
            retryAfter = now + TimeUnit.SECONDS.toNanos(3);
            LOG.debug("Could not play voice", failure);
        }

        private void closeOutput() {
            if (output != null) {
                output.flush();
                output.close();
                output = null;
            }
        }
    }

    private static final class Stream {
        final long start;
        final ArrayDeque<Received> queue = new ArrayDeque<>();
        long last;

        Stream(long start) {
            this.start = start;
        }
    }
}
