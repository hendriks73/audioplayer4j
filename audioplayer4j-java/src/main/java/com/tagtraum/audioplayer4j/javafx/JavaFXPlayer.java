/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.javafx;

import com.tagtraum.audioplayer4j.AudioDevice;
import com.tagtraum.audioplayer4j.AudioPlayer;
import com.tagtraum.audioplayer4j.AudioPlayerException;
import com.tagtraum.audioplayer4j.AudioPlayerListener;
import com.tagtraum.audioplayer4j.device.DefaultAudioDevice;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;
import javax.swing.event.SwingPropertyChangeSupport;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.time.Duration.ZERO;
import static java.time.temporal.ChronoUnit.NANOS;

/**
 * Plays an audio resource using JavaFX APIs.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class JavaFXPlayer implements AudioPlayer {

    private static final Logger LOG = Logger.getLogger(JavaFXPlayer.class.getName());

    private static boolean javaFXInitialized;
    private final PropertyChangeSupport propertyChangeSupport = new SwingPropertyChangeSupport(this, true);
    private final List<AudioPlayerListener> audioPlayerListeners = new ArrayList<>();
    private final Timer timer = new Timer(AudioPlayer.DEFAULT_MIN_TIME_EVENT_DIFFERENCE, new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            JavaFXUtilities.invokeLater(() -> {
                if (player != null && player.getStatus().equals(MediaPlayer.Status.PLAYING)) {
                    final java.time.Duration time = java.time.Duration.ofMillis((long)player.getCurrentTime().toMillis());
                    final java.time.Duration oldTime = JavaFXPlayer.this.time;
                    JavaFXPlayer.this.time = time;
                    JavaFXPlayer.this.propertyChangeSupport.firePropertyChange("time", oldTime, time);
                }
            });
        }
    });

    private URI song;
    private MediaPlayer player;
    private boolean ready = false;
    private boolean paused = true;
    private float volume = 1f;
    private float effectiveVolume = 1f;
    private float gain;
    private boolean muted;
    private java.time.Duration duration;
    private java.time.Duration time;
    private boolean unstarted;
    private boolean unfinished;

    private MediaException mediaException;

    public JavaFXPlayer() {
        initJavaFX();
    }

    /**
     * Initialize the JavaFX environment.
     */
    private static synchronized void initJavaFX() {
        if (!javaFXInitialized) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Runnable initFX = () -> {
                // implicitly initializes JavaFX environment
                new JFXPanel();
                latch.countDown();
            };
            if (SwingUtilities.isEventDispatchThread()) {
                initFX.run();
            } else {
                SwingUtilities.invokeLater(initFX);
            }
            try {
                latch.await();
                javaFXInitialized = true;
            } catch (InterruptedException e) {
                LOG.log(Level.SEVERE, "Interruption while initializing JavaFX. Initialization may not have completed.", e);
                throw new AudioPlayerException(e);
            }
        }
    }

    @Override
    public int getMinTimeEventDifference() {
        return timer.getDelay();
    }

    @Override
    public void setMinTimeEventDifference(final int minTimeEventDifference) {
        final int oldMinTimeEventDifference = this.timer.getDelay();
        this.timer.setDelay(minTimeEventDifference);
        this.propertyChangeSupport.firePropertyChange("minTimeEventDifference",
            oldMinTimeEventDifference, minTimeEventDifference);
    }

    @Override
    public void setAudioDevice(final AudioDevice audioDevice) throws IllegalArgumentException {
        Objects.requireNonNull(audioDevice, "AudioDevice must not be null");
        // apparently, JavaFX currently has no way of routing audio
        if (!DefaultAudioDevice.getInstance().equals(audioDevice)) throw new IllegalArgumentException("AudioDevice not supported: " + audioDevice);
    }

    @Override
    public AudioDevice getAudioDevice() {
        return DefaultAudioDevice.getInstance();
    }


    @Override
    public URI getURI() {
        return song;
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
        applyVolume();
        propertyChangeSupport.firePropertyChange("gain", oldGain, gain);
        propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
    }

    @Override
    public void open(final URI song) throws IOException, UnsupportedAudioFileException {
        if (song == null) {
            close();
            return;
        }

        final URI oldSong = this.song;
        final java.time.Duration oldDuration = this.duration;
        final boolean oldPaused = paused;

        try {

            // get URL and special case file: protocol
            final String url = song.toString();
            if (url.startsWith("file:")) {
                final Path file = Paths.get(song);
                if (Files.notExists(file) || !Files.isReadable(file)) throw new FileNotFoundException(file.toString());
            }
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                if (player != null) {
                    player.stop();
                    fireFinished();
                    player.dispose();
                    player = null;
                }
                unstarted = true;
                unfinished = true;
                setReady(false);
                final Media media = new Media(url);
                player = new MediaPlayer(media);
                applyVolume();
                player.setMute(muted);
                player.setOnEndOfMedia(this::close);
                player.setOnReady(() -> setReady(true));
                player.setOnError(() -> setReady(player.getError()));
                player.setOnPaused(() -> {
                    final boolean oldPaused1 = paused;
                    paused = true;
                    propertyChangeSupport.firePropertyChange("paused", oldPaused1, paused);
                });
                return null;
            });
            waitUntilReady();
            this.duration = java.time.Duration.of((long)player.getTotalDuration().toMillis() * 1000L * 1000L, NANOS);
            this.song = song;
            if (!oldPaused) {
                play();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MediaException) {
                final MediaException mediaException = (MediaException) e.getCause();
                final UnsupportedAudioFileException unsupportedAudioFileException = new UnsupportedAudioFileException(song.toString());
                unsupportedAudioFileException.initCause(mediaException);
                throw unsupportedAudioFileException;
            }
            if (e.getCause() instanceof UnsupportedOperationException) {
                throw new IOException("Failed to open " + song, e.getCause());
            }
            if (e.getCause() instanceof IllegalArgumentException) {
                throw new IOException("Failed to open " + song, e.getCause());
            }
            this.song = null;
            this.duration = null;
            throw new IOException(e.getCause());
        } finally {
            propertyChangeSupport.firePropertyChange("uri", oldSong, this.song);
            propertyChangeSupport.firePropertyChange("duration", oldDuration, this.duration);
            propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);
        }
    }

    @Override
    public void play() {
        if (player == null) {
            throw new IllegalStateException("Player wasn't opened.");
        }
        fireStarted();
        timer.start();
        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                final boolean oldPaused = paused;
                player.play();
                paused = false;
                propertyChangeSupport.firePropertyChange("paused", oldPaused, paused);
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    @Override
    public void pause() {
        timer.stop();
        if (player == null) return;
        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                player.pause();
                // paused will be fired by onPause handler.
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    @Override
    public void playPause() {
        if (isPaused()) play();
        else pause();
    }

    @Override
    public void close() {
        if (player == null) {
            // player is not open, nothing to close
            return;
        }
        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                player.stop();
                player.dispose();
                player = null;
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
        }
        fireFinished();
        setReady(false);
        final boolean oldPaused = this.paused;
        final URI oldSong = song;
        final java.time.Duration oldDuration = this.duration;
        final java.time.Duration oldTime = this.time;
        this.time = null;
        this.song = null;
        this.duration = null;
        this.paused = true;
        propertyChangeSupport.firePropertyChange("uri", oldSong, this.song);
        propertyChangeSupport.firePropertyChange("duration", oldDuration, this.duration);
        propertyChangeSupport.firePropertyChange("time", oldTime, this.time);
        propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);
    }

    @Override
    public void reset() {
        if (player == null) {
            throw new IllegalStateException("Player wasn't opened.");
        }
        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                final long oldTime = (long)player.getCurrentTime().toMillis();
                player.seek(new Duration(0));
                time = java.time.Duration.ZERO;
                propertyChangeSupport.firePropertyChange("time", oldTime, this.time);
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    @Override
    public java.time.Duration getTime() {
        try {
            return JavaFXUtilities.invokeAndWait(() -> player == null ? null : java.time.Duration.ofMillis((long)player.getCurrentTime().toMillis()));
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    @Override
    public void setTime(final java.time.Duration time) {
        Objects.requireNonNull(time, "Cannot set time to null");
        if (time.isNegative()) throw new IllegalArgumentException("Time must not be less than zero: " + time);
        if (player == null) throw new IllegalStateException("No resource loaded");
        if (duration != null && time.compareTo(duration) > 0) {
            throw new IllegalArgumentException("Time (" + time + ") must not be greater than duration (" + duration + ")");
        }

        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                final java.time.Duration oldTime = this.time;
                player.seek(new Duration(time.toMillis()));
                this.time = time;
                propertyChangeSupport.firePropertyChange("time", oldTime, time);
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    @Override
    public java.time.Duration getDuration() {
        return duration;
    }

    private synchronized void setReady(final boolean ready) {
        this.ready = ready;
        this.mediaException = null;
        this.notifyAll();
        if (ready) {
            final java.time.Duration oldTime = this.time;
            this.time = ZERO;
            propertyChangeSupport.firePropertyChange("time", oldTime, time);
        }
    }

    private synchronized void setReady(final javafx.scene.media.MediaException exception) {
        this.ready = false;
        this.mediaException = exception;
        LOG.log(Level.SEVERE, "Player not ready.", exception);
        this.notifyAll();
    }

    private synchronized void waitUntilReady() throws UnsupportedAudioFileException, IOException {
        final long start = System.currentTimeMillis();
        while (!ready && mediaException == null && System.currentTimeMillis() - start < 5000) {
            try {
                wait(1000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        if (mediaException != null) {
            final UnsupportedAudioFileException e = new UnsupportedAudioFileException(mediaException.getMessage());
            e.initCause(mediaException);
            throw e;
        }
        if (!ready) {
            throw new IOException("Timeout while trying to open song.");
        }
    }

    @Override
    public boolean isPaused() {
        return this.paused;
    }

    @Override
    public float getVolume() {
        return this.volume;
    }

    @Override
    public float getEffectiveVolume() {
        return this.effectiveVolume;
    }

    @Override
    public void setVolume(final float volume) {
        if (volume < 0.0f || volume > 1.0f) throw new IllegalArgumentException("Volume has to be >= 0.0 and <= 1.0: " + volume);
        final float oldVolume = this.volume;
        final float oldEffectiveVolume = this.effectiveVolume;
        this.volume = volume;
        this.effectiveVolume = addGain(JavaFXPlayer.this.volume);
        if (player == null) {
            propertyChangeSupport.firePropertyChange("volume", oldVolume, volume);
        } else {
            applyVolume();
            propertyChangeSupport.firePropertyChange("volume", oldVolume, volume);
        }
        propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
        if (this.muted && volume > 0f) {
            setMuted(false);
        }
    }

    private void applyVolume() {
        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                if (this.player != null) {
                    this.player.setVolume(addGain(JavaFXPlayer.this.volume));
                }
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
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

    @Override
    public void setMuted(final boolean muted) {
        this.muted = muted;
        if (player == null) return;
        try {
            JavaFXUtilities.invokeAndWait((Callable<Void>) () -> {
                final boolean oldMuted = player.isMute();
                player.setMute(muted);
                propertyChangeSupport.firePropertyChange("muted", oldMuted, muted);
                return null;
            });
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    @Override
    public boolean isMuted() {
        try {
            return JavaFXUtilities.invokeAndWait(() -> player == null ? muted : player.isMute());
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, e.toString(), e);
            throw new AudioPlayerException(e.getCause());
        }
    }

    public void addPropertyChangeListener(final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(propertyChangeListener);
    }

    public void removePropertyChangeListener(final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(propertyChangeListener);
    }

    public void addPropertyChangeListener(final String propertyName, final PropertyChangeListener propertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(propertyName, propertyChangeListener);
    }

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
            final URI s = song;
            SwingUtilities.invokeLater(() -> {
                for (final AudioPlayerListener listener : audioPlayerListeners) {
                    listener.started(JavaFXPlayer.this, s);
                }
            });
        }
    }

    private void fireFinished() {
        if (unfinished && !unstarted) {
            unfinished = false;
            final URI s = song;
            SwingUtilities.invokeLater(() -> {
                for (final AudioPlayerListener listener : audioPlayerListeners) {
                    listener.finished(JavaFXPlayer.this, s);
                }
            });
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            "uri=" + song +
            ", volume=" + volume +
            ", paused=" + paused +
            ", duration=" + duration +
            ", gain=" + gain +
            "dB" +
            '}';
    }
}