/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.macos;

import com.tagtraum.audioplayer4j.*;
import com.tagtraum.audioplayer4j.device.DefaultAudioDevice;

import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;
import javax.swing.event.SwingPropertyChangeSupport;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Timer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Natively-backed audio player for macOS.
 * Property changes like time, duration, and uri can be listened to
 * via {@link PropertyChangeListener}s, playback events are accessible via
 * {@link AudioPlayerListener}s.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class AVFoundationPlayer implements AudioPlayer {

    private static final Logger LOG = Logger.getLogger(AVFoundationPlayer.class.getName());
    private static final AtomicInteger id = new AtomicInteger(0);

    static {
        NativeLibraryLoader.loadLibrary();
        // init toolkit, to avoid potential deadlocks later on
        Toolkit.getDefaultToolkit();
    }

    private Cleaner.Cleanable nativeResource;
    private final Cleaner instanceCleaner;
    private final PropertyChangeSupport propertyChangeSupport = new SwingPropertyChangeSupport(this, true);
    private final ExecutorService serializer;
    private final List<AudioPlayerListener> audioPlayerListeners = new ArrayList<>();
    private long[] pointers;
    private Timer task;
    private float volume = 1f;
    private float effectiveVolume = 1f;
    private Duration duration;
    private boolean paused = true;
    private Instant unpausedTime;
    private boolean muted;
    private Duration time;
    private float gain = 0;
    private URI songURI;
    private boolean endOfMedia;
    private boolean unstarted;
    private boolean unfinished;
    private int minTimeEventDifference = DEFAULT_MIN_TIME_EVENT_DIFFERENCE;

    /**
     * Create an instance using a private new execution thread and a private
     * {@link Cleaner} instance.
     */
    public AVFoundationPlayer() {
        this(java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> new Thread(r, "Player Thread " + id.incrementAndGet())
        ), Cleaner.create());
    }

    /**
     * Create an instance using a private new execution thread.
     *
     * @param cleaner cleaner instance used to ensure proper clean up when this instance becomes
     *                eligible for garbage collection
     */
    public AVFoundationPlayer(final Cleaner cleaner) {
        this(java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> new Thread(r, "Player Thread " + id.incrementAndGet())
        ), cleaner);
    }

    /**
     * Create an instance using the specified serializer and cleaner instances.
     *
     * @param serializer executor service used for all native calls
     * @param cleaner cleaner instance used to ensure proper clean up when this instance becomes
     *                eligible for garbage collection
     */
    public AVFoundationPlayer(final ExecutorService serializer, final Cleaner cleaner) {
        if (serializer.isShutdown() || serializer.isTerminated()) {
            throw new IllegalArgumentException("Serializer service must be alive: " + serializer);
        }
        this.serializer = serializer;
        this.instanceCleaner = cleaner;
    }

    @Override
    public int getMinTimeEventDifference() {
        return minTimeEventDifference;
    }

    @Override
    public void setMinTimeEventDifference(final int minTimeEventDifference) {
        final int oldMinTimeEventDifference = this.minTimeEventDifference;
        this.minTimeEventDifference = minTimeEventDifference;
        this.propertyChangeSupport.firePropertyChange("minTimeEventDifference",
            oldMinTimeEventDifference, this.minTimeEventDifference);
    }

    @Override
    public void setAudioDevice(final AudioDevice audioDevice) throws IllegalArgumentException {
        Objects.requireNonNull(audioDevice, "AudioDevice must not be null");
        if (!DefaultAudioDevice.getInstance().equals(audioDevice)) throw new IllegalArgumentException("Audio device not supported: " + audioDevice);
    }

    @Override
    public AudioDevice getAudioDevice() {
        return DefaultAudioDevice.getInstance();
    }

    /**
     * Open the given URI and prepare for playback.
     * If this player is <em>unpaused</em>, playback starts as soon as possible.
     * Any resource that was previously loaded is replaced.
     *
     * @param newURI URI of an audio resource
     * @throws IOException if something goes wrong while opening
     */
    @Override
    public void open(final URI newURI) throws IOException, UnsupportedAudioFileException {
        if (LOG.isLoggable(Level.FINE)) LOG.fine("Trying to open " + newURI);

        if (newURI == null) {
            close();
            return;
        }

        // save old values
        final URI oldURI = this.songURI;
        final Duration oldDuration = this.duration;
        final boolean oldPaused = this.paused;

        // get URL and special case file: protocol
        final String url = newURI.toString();
        if (url.startsWith("file:")) {
            final Path file = Paths.get(newURI);
            if (Files.notExists(file) || !Files.isReadable(file)) throw new FileNotFoundException(file.toString());
        }
        try {
            serializeIOCall(() -> {
                if (LOG.isLoggable(Level.INFO)) LOG.info("Opening " + newURI);
                stopAndClean();
                fireFinished();

                final long openStart = System.currentTimeMillis();
                openWithCleaner(url);
                if (LOG.isLoggable(Level.FINE)) LOG.fine("Time to open " + url + ": " + (System.currentTimeMillis()-openStart) + "ms");

                this.endOfMedia = false;
                this.unstarted = true;
                this.unfinished = true;

                this.time = null;
                this.songURI = newURI;

                // calculating duration may take some time
                final long durationStart = System.currentTimeMillis();
                final long d = getDuration(pointers[0]);
                this.duration = d < 0 ? null : Duration.ofMillis(d);
                if (LOG.isLoggable(Level.FINE)) LOG.fine("Time to load duration of " + url +": " + (System.currentTimeMillis()-durationStart) + "ms");

                // adjust to the previous volume
                applyVolume();
                setMuted(pointers[0], muted);
                setTime(pointers[0], 0L);

                propertyChangeSupport.firePropertyChange("uri", oldURI, songURI);
                propertyChangeSupport.firePropertyChange("duration", oldDuration, this.duration);

                if (!paused) {
                    play(pointers[0]);
                }
                return null;
            });
        } catch (Exception e) {
            this.paused = true;
            this.songURI = null;
            this.duration = null;

            this.propertyChangeSupport.firePropertyChange("uri", oldURI, this.songURI);
            this.propertyChangeSupport.firePropertyChange("duration", oldDuration, this.duration);
            this.propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);

            if (e instanceof IOException) {
                throw (IOException)e;
            }
            if (e instanceof UnsupportedAudioFileException) {
                throw (UnsupportedAudioFileException)e;
            }
            throw new IOException(e);
        }
    }

    /**
     * URI of the currently loaded resource.
     *
     * @return URI or null
     */
    @Override
    public URI getURI() {
        return songURI;
    }

    private void stopAndClean() {
        if (this.pointers != null) {
            try {
                stop(this.pointers[0]);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            } finally {
                this.pointers = null;
                if (this.nativeResource != null) {
                    this.nativeResource.clean();
                    this.nativeResource = null;
                }
            }
        }
    }

    private void openWithCleaner(final String url) throws IOException, UnsupportedAudioFileException {
        if (this.nativeResource != null) {
            this.pointers = null;
            this.nativeResource.clean();
            this.nativeResource = null;
        }
        this.pointers = open(url);
        if (this.pointers != null) {
            LOG.log(Level.INFO, "Pointers: " + Arrays.toString(this.pointers));
            this.nativeResource = instanceCleaner.register(this, new Destroyer(this.pointers));
        }
    }

    /**
     * Start playback of the loaded resource.
     *
     * @throws IllegalStateException if no resource is loaded
     */
    @Override
    public void play() throws IllegalStateException {
        if (pointers != null)
            serializeCall(() -> {
                play(pointers[0]);
                return null;
            });
    }

    private void play(final long pointer) throws IOException, IllegalStateException {
        if (pointer != 0) {
            if (LOG.isLoggable(Level.INFO)) LOG.info("Starting " + songURI);
            start(pointer);
            fireStarted();
            if (this.task != null) {
                this.task.purge();
            } else {
                this.task = new Timer();
            }
            this.task.schedule(new TaskCall(), minTimeEventDifference);
            final boolean oldPaused = this.paused;
            this.paused = false;
            this.unpausedTime = Instant.now();
            this.propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);
        } else {
            throw new IllegalStateException("No resource loaded");
        }
    }

    /**
     * Pause playback.
     *
     * @throws IllegalStateException if no resource is loaded
     */
    @Override
    public void pause() throws IllegalStateException {
        if (pointers != null) {
            serializeCall(() -> {
                if (pointers != null) {
                    if (LOG.isLoggable(Level.INFO)) LOG.info("Stopping " + this);
                    stop(AVFoundationPlayer.this.pointers[0]);
                    if (LOG.isLoggable(Level.INFO)) LOG.info("Called stop");
                    final boolean oldPaused = paused;
                    paused = true;
                    propertyChangeSupport.firePropertyChange("paused", oldPaused, true);
                }
                return null;
            });
        }
        if (task != null) {
            if (LOG.isLoggable(Level.INFO)) LOG.info("Cancelling task.");
            task.cancel();
            if (LOG.isLoggable(Level.INFO)) LOG.info("Cancelled task.");
        }
        task = null;
    }

    /**
     * Paused or not.
     *
     * @return true, if playback is paused
     */
    @Override
    public boolean isPaused() {
        return paused;
    }

    /**
     * Toggle playback (play/pause).
     *
     * @throws IllegalStateException if no resource is loaded
     */
    @Override
    public void playPause() throws IllegalStateException {
        if (isPaused()) play();
        else pause();
    }

    /**
     * Reset playback to the beginning of the resource.
     */
    @Override
    public void reset() {
        if (pointers != null) {
            serializeCall(() -> {
                if (pointers != null) {
                    if (LOG.isLoggable(Level.INFO)) LOG.info("Resetting " + songURI);
                    this.time = Duration.ofMillis(getTime(AVFoundationPlayer.this.pointers[0]));
                    setTime(pointers[0], 0L);
                }
                return null;
            });
        } else {
            throw new IllegalStateException("No resource loaded");
        }
    }

    /**
     * Get current time.
     *
     * @return time
     */
    @Override
    public Duration getTime() {
        if (pointers == null) {
            return this.time;
        }
        else {
            return serializeCall(() -> pointers != null ? Duration.ofMillis(getTime(pointers[0])) : this.time);
        }
    }

    /**
     * Seek the given time.
     *
     * @param time time
     */
    @Override
    public void setTime(final Duration time) {
        Objects.requireNonNull(time, "Cannot set time to null");
        if (time.isNegative()) throw new IllegalArgumentException("Time must not be less than zero: " + time);
        if (pointers == null) throw new IllegalStateException("No resource loaded");
        if (duration != null && time.compareTo(duration) > 0) {
            throw new IllegalArgumentException("Time (" + time + ") must not be greater than duration (" + duration + ")");
        }

        serializeCall(() -> {
            AVFoundationPlayer.this.time = Duration.ofMillis(getTime(this.pointers[0]));
            setTime(pointers[0], time.toMillis());
            return null;
        });
    }

    /**
     * Get duration.
     *
     * @return duration, null if unknown
     */
    @Override
    public Duration getDuration() {
        return duration;
    }

    @Override
    public float getEffectiveVolume() {
        return this.effectiveVolume;
    }

    @Override
    public float getVolume() {
        return volume;
    }

    @Override
    public void setVolume(final float volume) {
        if (volume < 0.0f || volume > 1.0f) throw new IllegalArgumentException("Volume has to be >= 0.0 and <= 1.0: " + volume);
        final float oldEffectiveVolume = this.effectiveVolume;
        final float oldVolume = this.volume;
        this.volume = volume;
        this.effectiveVolume = addGain(this.volume);
        if (pointers != null) {
            serializeCall(() -> {
                applyVolume();
                propertyChangeSupport.firePropertyChange("volume", oldVolume, volume);
                propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
                return null;
            });
        } else {
            propertyChangeSupport.firePropertyChange("volume", oldVolume, volume);
            propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
        }
        if (this.muted && volume > 0f) {
            setMuted(false);
        }
    }

    /**
     * Apply the java-side volume + gain to the AVPlayer instance.
     * Must be called from serialize call.
     *
     * @throws IOException if something goes wrong
     */
    private void applyVolume() throws IOException {
        if (pointers != null) {
            setVolume(pointers[0], addGain(this.volume));
        }
    }

    @Override
    public float getGain() {
        return gain;
    }

    @Override
    public void setGain(final float gain) {
        final float oldGain = this.gain;
        final float oldEffectiveVolume = this.effectiveVolume;
        this.gain = gain;
        this.effectiveVolume = addGain(this.volume);
        this.propertyChangeSupport.firePropertyChange("gain", oldGain, gain);
        // update the effective volume
        if (pointers != null) {
            serializeCall(() -> {
                applyVolume();
                propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
                return null;
            });
        } else {
            propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
        }
    }

    @Override
    public boolean isMuted() {
        if (pointers == null) return muted;
        return serializeCall(() -> {
            if (pointers == null) return muted;
            else muted = isMuted(pointers[0]);
            return muted;
        });
    }

    @Override
    public void setMuted(final boolean muted) {
        final boolean oldMuted = this.muted;
        this.muted = muted;
        if (pointers != null) {
            serializeCall(() -> {
                if (pointers != null) {
                    final boolean oldMuted1 = isMuted(pointers[0]);
                    setMuted(pointers[0], muted);
                    propertyChangeSupport.firePropertyChange("muted", oldMuted1, muted);
                }
                return null;
            });
        } else {
            propertyChangeSupport.firePropertyChange("muted", oldMuted, muted);
        }
    }

    private float addGain(final float volume) {
        float localGain = AudioPlayer.volumeToGain(volume);
        localGain += gain;
        if (localGain > 0) {
            localGain = 0;
        }
        return AudioPlayer.gainToVolume(localGain);
    }

    private PlayerStatus getStatus() {
        try {
            return PlayerStatus.values()[getStatus(this.pointers[0])];
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            return PlayerStatus.UNKNOWN;
        }
    }

    private PlayerTimeControlStatus getTimeControlStatus() {
        try {
            return PlayerTimeControlStatus.values()[getTimeControlStatus(this.pointers[0])];
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            return PlayerTimeControlStatus.PAUSED;
        }
    }

    /**
     * Close this player and free all native resources connected to it.
     */
    @Override
    public void close() {
        if (this.pointers == null) {
            // player is not open, nothing to close
            return;
        }
        if (LOG.isLoggable(Level.INFO)) LOG.info("Closing " + this);
        try {
            pause();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.toString(), e);
        }
        fireFinished();
        final URI oldURI = songURI;
        final Duration oldDuration = duration;
        final Duration oldTime = time;
        this.songURI = null;
        this.duration = null;
        this.time = null;

        if (this.nativeResource != null) {
            final Future<?> future = serializer.submit(() -> {
                if (this.nativeResource != null) {
                    this.nativeResource.clean();
                    this.nativeResource = null;
                    this.pointers = null;
                }
                propertyChangeSupport.firePropertyChange("uri", oldURI, songURI);
                propertyChangeSupport.firePropertyChange("duration", oldDuration, duration);
                propertyChangeSupport.firePropertyChange("time", oldTime, time);
                return null;
            });
            try {
                future.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            }
        }
    }

    /**
     * Blocking call on the serialization thread, which may
     * throw an {@link IOException}.
     *
     * @param callable callable
     * @param <T> result type
     * @return results
     * @throws IOException if something goes wrong underneath.
     * @throws AudioPlayerException if something goes wrong
     */
    private <T> T serializeIOCall(final Callable<T> callable) throws IOException, AudioPlayerException, UnsupportedAudioFileException {
        T result = null;
        try {
            result = serializer.submit(callable).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof UnsupportedAudioFileException) throw (UnsupportedAudioFileException) cause;
            throw new AudioPlayerException(cause);
        } catch (TimeoutException e) {
            throw new IOException(e);
        }
        return result;
    }

    /**
     * Blocking call on the serialization thread.
     *
     * @param callable callable
     * @param <T> result type
     * @return results
     * @throws AudioPlayerException if something goes wrong
     */
    private <T> T serializeCall(final Callable<T> callable) throws AudioPlayerException {
        T result = null;
        try {
            result = serializer.submit(callable).get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AudioPlayerException(cause);
        } catch (TimeoutException e) {
            throw new AudioPlayerException(e);
        }
        return result;
    }

    private native long[] open(final String url) throws IOException, UnsupportedAudioFileException;
    private static native void start(final long pointer) throws IOException;
    private static native void stop(final long pointer) throws IOException;
    private static native void reset(final long pointer) throws IOException;
    private static native void setMuted(final long pointer, final boolean muted) throws IOException;
    private static native boolean isMuted(final long pointer) throws IOException;
    private static native void setVolume(final long pointer, final float volume) throws IOException;
    private static native float getVolume(final long pointer) throws IOException;
    private native void setTime(final long pointer, final long time) throws IOException;
    private static native long getTime(final long pointer) throws IOException;
    private static native long getDuration(final long pointer) throws IOException;
    private static native boolean isPlaying(final long pointer) throws IOException;
    private static native void close(final long[] pointers) throws IOException;
    private static native int getStatus(final long pointer) throws IOException;
    private static native int getTimeControlStatus(final long pointer) throws IOException;
    private static native String getReasonForWaitingToPlay(final long pointer) throws IOException;
    private static native String getError(final long pointer) throws IOException;
    private static native void playImmediatelyAtRate(final long pointer, final float rate) throws IOException;

    private void didPlayToEndTime() {
        // called from native
        if (LOG.isLoggable(Level.FINE)) LOG.fine("Native callback \"didPlayToEndTime\"");
        this.endOfMedia = true;
        fireFinished();
    }

    private void fireTime(final long milliseconds) {
        // called from native
        fireTime(Duration.ofMillis(milliseconds));
    }

    private void fireTime(final Duration time) {
        final Duration oldTime = this.time;
        this.time = time;
        propertyChangeSupport.firePropertyChange("time", oldTime, time);
    }

    @Override
    public void addPropertyChangeListener(final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(propertyChangeListener);
    }

    @Override
    public void removePropertyChangeListener(final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(propertyChangeListener);
    }

    @Override
    public void addPropertyChangeListener(final String propertyName, final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(propertyName, propertyChangeListener);
    }

    @Override
    public void removePropertyChangeListener(final String propertyName, final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(propertyName, propertyChangeListener);
    }

    @Override
    public void addAudioPlayerListener(final AudioPlayerListener listener) {
        audioPlayerListeners.add(listener);
    }

    @Override
    public void removeAudioPlayerListener(final AudioPlayerListener listener) {
        audioPlayerListeners.remove(listener);
    }

    private void fireStarted() {
        if (unstarted) {
            unstarted = false;
            final URI s = songURI;
            SwingUtilities.invokeLater(() -> {
                for (final AudioPlayerListener listener : audioPlayerListeners) {
                    listener.started(AVFoundationPlayer.this, s);
                }
            });
        }
    }

    private void fireFinished() {
        if (unfinished && !unstarted) {
            unfinished = false;
            final URI s = songURI;
            SwingUtilities.invokeLater(() -> {
                for (final AudioPlayerListener listener : audioPlayerListeners) {
                    listener.finished(AVFoundationPlayer.this, s, this.endOfMedia);
                }
            });
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            "uri=" + songURI +
            ", volume=" + volume +
            ", paused=" + paused +
            ", duration=" + duration +
            ", gain=" + gain +
            "dB, pointers=" + Arrays.toString(pointers) +
            '}';
    }

    private class TaskCall extends TimerTask {

        @Override
        public void run() {
            if (pointers != null && songURI != null) {
                try {
                    serializeCall(() -> {
                        if (pointers != null) {
                            final long timeInMillis = getTime(AVFoundationPlayer.this.pointers[0]);
                            AVFoundationPlayer.this.fireTime(Duration.ofMillis(timeInMillis));

                            final boolean playing = pointers != null && isPlaying(AVFoundationPlayer.this.pointers[0]);
                            if (playing) {
                                if (pointers != null && songURI != null) {
                                    task.schedule(new TaskCall(), minTimeEventDifference);
                                }

                                // check for errors and restart of necessary
                                final PlayerStatus status = AVFoundationPlayer.this.getStatus();
                                if (status == PlayerStatus.FAILED) {
                                    LOG.log(Level.SEVERE, "Player failed: " + getError(AVFoundationPlayer.this.pointers[0]));
                                }
                                final PlayerTimeControlStatus timeControlStatus = AVFoundationPlayer.this.getTimeControlStatus();
                                final Duration timeSinceUnpause = Duration.between(unpausedTime, Instant.now());
                                if (timeControlStatus == PlayerTimeControlStatus.WAITING_TO_PLAY) {
                                    LOG.info("Player waiting to play: " + getReasonForWaitingToPlay(AVFoundationPlayer.this.pointers[0]));
                                } else if (timeControlStatus == PlayerTimeControlStatus.PAUSED && !paused && timeSinceUnpause.toMillis() >= 500L && timeSinceUnpause.toSeconds() < 3) {
                                    if (LOG.isLoggable(Level.FINE)) LOG.fine("Player is paused, even though we still think it's playing. Calling playImmediatelyAtRate: 1f...");
                                    playImmediatelyAtRate(AVFoundationPlayer.this.pointers[0], 1f);
                                } else if (timeControlStatus == PlayerTimeControlStatus.PAUSED && !paused && timeSinceUnpause.toSeconds() >= 3) {
                                    if (LOG.isLoggable(Level.FINE)) LOG.fine("Player is paused, even though we still think it's playing. Restarting resource... (" + songURI + ")");
                                    try {
                                        // let's try to restart this...
                                        task.purge();
                                        openWithCleaner(AVFoundationPlayer.this.songURI.toString());
                                        setTime(AVFoundationPlayer.this.pointers[0], timeInMillis);
                                        play(AVFoundationPlayer.this.pointers[0]);
                                    } catch (Exception e) {
                                        LOG.log(Level.SEVERE, "Failed to restart player after weird pause.");
                                        AVFoundationPlayer.this.paused = true;
                                        propertyChangeSupport.firePropertyChange("paused", false, true);
                                    }
                                }
                            }
                        } else {
                            LOG.log(Level.WARNING, "Attempt to run TaskCall with zero pointer.");
                        }
                        return null;
                    });
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e.toString(), e);
                }
            } else {
                LOG.log(Level.WARNING,"Attempt to run TaskCall with pointers=" + Arrays.toString(pointers) + " and uri= " + songURI);
            }
        }
    }


    /**
     * Hook that ensures native resources are eventually freed.
     * This is the Java 9 equivalent of the deprecated {@link #finalize()}.
     */
    private static class Destroyer implements Runnable {

        private final long[] pointers;

        public Destroyer(final long[] pointers) {
            this.pointers = pointers;
        }

        @Override
        public void run() {
            if (pointers != null) {
                try {
                    close(pointers);
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Failed to destroy native AVPlayer object.", e);
                }
            }
        }
    }

    /**
     * PlayerStatus.
     */
    enum PlayerStatus {
        UNKNOWN, READY_TO_PLAY, FAILED
    }

    /**
     * PlayerTimeControlStatus.
     */
    enum PlayerTimeControlStatus {
        PAUSED, WAITING_TO_PLAY, PLAYING
    }
}
