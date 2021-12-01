/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.java;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.time.Duration.ZERO;
import static java.time.temporal.ChronoUnit.MICROS;

/**
 * Ensure single-threaded access to a wrapped {@link AudioInputStream}.
 */
public class SingleThreadedAudioInputStream implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SingleThreadedAudioInputStream.class.getName());
    private static final AtomicInteger id = new AtomicInteger(0);

    private final AtomicLong frameNumber = new AtomicLong(0);
    private final ExecutorService serializer;
    private final AudioInputStream stream;
    private final AudioFormat format;
    private final BlockingQueue<AudioChunk> bufferQueue = new LinkedBlockingQueue<>();
    private String originalStream;

    public SingleThreadedAudioInputStream(final URL url, final AudioFormat format) throws ExecutionException, InterruptedException, IOException, UnsupportedAudioFileException {
        try {
            this.serializer = Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "SingleThreadedAudioInputStream-" + id.incrementAndGet() + " " + url);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
            final Future<AudioInputStream> f = this.serializer.submit(() -> openStream(ExtAudioSystem.getAudioInputStream(url, 32 * 1024), format));
            this.stream = f.get(1, TimeUnit.SECONDS);
            this.format = stream.getFormat();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnsupportedAudioFileException) throw (UnsupportedAudioFileException)e.getCause();
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw e;
        } catch (TimeoutException e) {
            throw new IOException(e);
        }
    }

    public long getFrameNumber() {
        return frameNumber.get();
    }

    /**
     * Attempt to open an audio inputstream with a certain format.
     *
     * @param audioInputStream audio input stream
     * @return audio inputstream
     */
    private AudioInputStream openStream(final AudioInputStream audioInputStream, final AudioFormat format) {
        if (LOG.isLoggable(Level.FINE))
            LOG.fine("Converting stream " + audioInputStream + " with " + audioInputStream.getFormat() + " to " + format);
        AudioInputStream stream = audioInputStream;
        try {
            this.originalStream = stream.toString();
            AudioFormat streamFormat = stream.getFormat();
            // ensure signed pcm
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(streamFormat.getEncoding())) {
                stream = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, stream);
            }
            // ensure SampleRate
            streamFormat = stream.getFormat();
            if (streamFormat.getSampleRate() != format.getSampleRate() && streamFormat.getSampleRate() != AudioSystem.NOT_SPECIFIED) {
                stream = ExtAudioSystem.getAudioInputStream(new AudioFormat(
                    streamFormat.getEncoding(),
                    format.getSampleRate(),
                    streamFormat.getSampleSizeInBits(),
                    streamFormat.getChannels(),
                    streamFormat.getFrameSize(),
                    format.getSampleRate(),
                    streamFormat.isBigEndian(),
                    streamFormat.properties()
                ), stream);
            }
            // ensure SampleSizeInBits
            streamFormat = stream.getFormat();
            if (streamFormat.getSampleSizeInBits() != format.getSampleSizeInBits() && streamFormat.getSampleSizeInBits() != AudioSystem.NOT_SPECIFIED
                || streamFormat.getChannels() != format.getChannels() && streamFormat.getChannels() != AudioSystem.NOT_SPECIFIED) {
                stream = ExtAudioSystem.getAudioInputStream(new AudioFormat(
                    streamFormat.getEncoding(),
                    streamFormat.getSampleRate(),
                    format.getSampleSizeInBits(),
                    format.getChannels(),
                    format.getFrameSize(),
                    streamFormat.getFrameRate(),
                    streamFormat.isBigEndian(),
                    streamFormat.properties()
                ), stream);
            }
            streamFormat = stream.getFormat();
            if (streamFormat.isBigEndian() != format.isBigEndian()) {
                stream = ExtAudioSystem.getAudioInputStream(new AudioFormat(
                    streamFormat.getEncoding(),
                    streamFormat.getSampleRate(),
                    streamFormat.getSampleSizeInBits(),
                    streamFormat.getChannels(),
                    streamFormat.getFrameSize(),
                    streamFormat.getFrameRate(),
                    format.isBigEndian(),
                    streamFormat.properties()
                ), stream);
            }
            return stream;
        } catch (RuntimeException e) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e1) {
                    LOG.log(Level.SEVERE, e1.toString(), e1);
                }
            }
            throw e;
        }
    }


    public boolean isSeekable() {
        try {
            return (Boolean) stream.getClass().getMethod("isSeekable").invoke(stream);
        } catch (Exception e) {
            return false;
        }
    }

    public void seek(final Duration duration) throws IOException {
        bufferQueue.clear();
        try {
            final Future<Void> f = this.serializer.submit(() -> {
                if (frameNumber.get() == 0 && duration.equals(ZERO)) {
                    LOG.warning("Unnecessary seek");
                    return null;
                }
                final TimeUnit timeUnit = TimeUnit.MICROSECONDS;
                final long durationMS = duration.dividedBy(MICROS.getDuration());
                stream.getClass().getMethod("seek", Long.TYPE, TimeUnit.class).invoke(stream, durationMS, timeUnit);
                frameNumber.set((long) (timeUnit.toMillis(durationMS) * format.getSampleRate() / 1000f));
                return null;
            });
            f.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            throw new IOException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InvocationTargetException) {
                final InvocationTargetException targetException = (InvocationTargetException) e.getCause();
                if (targetException.getTargetException() instanceof IOException) {
                    throw (IOException) targetException.getTargetException();
                } else if (targetException.getTargetException() instanceof RuntimeException) {
                    throw (RuntimeException) targetException.getTargetException();
                } else {
                    throw new IOException(targetException.getTargetException());
                }
            } else if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            } else if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else {
                throw new IOException(e);
            }
        }
    }


    public int read(final byte[] buf) throws IOException {
        // make sure we have something to read
        if (bufferQueue.isEmpty()) readAhead(buf.length);
        try {
            final AudioChunk chunk = bufferQueue.poll(15 * 1000, TimeUnit.SECONDS);
            if (chunk == null) {
                LOG.log(Level.SEVERE, "chunk == null");
                return -1;
            }
            if (chunk.getLength() >= 0) {
                System.arraycopy(chunk.getBuf(), 0, buf, 0, chunk.getLength());
                // read next chunk
                readAhead(buf.length);
            }
            frameNumber.addAndGet(chunk.getLength() / format.getFrameSize());
            return chunk.getLength();
        } catch (InterruptedException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
        }
        return -1;
    }

    private void readAhead(final int length) {
        this.serializer.submit(() -> {
            try {
                final byte[] readAheadBuffer = new byte[length];
                final int justRead = stream.read(readAheadBuffer);
                bufferQueue.put(new AudioChunk(justRead, readAheadBuffer));
            } catch (IOException | InterruptedException e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            }
            return null;
        });
    }

    public void close() {
        this.serializer.submit(() -> {
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
            return null;
        });
        this.serializer.shutdown();
    }

    @Override
    public String toString() {
        return "SingleThreadedAudioInputStream{" +
            "originalStream='" + originalStream + '\'' +
            ", stream=" + stream +
            ", format=" + format +
            ", frameNumber=" + frameNumber +
            '}';
    }

    private static class AudioChunk {
        private final int length;
        private final byte[] buf;

        public AudioChunk(final int length, final byte[] buf) {
            this.length = length;
            this.buf = buf;
        }

        public int getLength() {
            return length;
        }

        public byte[] getBuf() {
            return buf;
        }
    }
}
