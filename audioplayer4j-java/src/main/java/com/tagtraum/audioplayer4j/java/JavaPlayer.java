/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.java;

import com.tagtraum.audioplayer4j.AudioDevice;
import com.tagtraum.audioplayer4j.AudioPlayer;
import com.tagtraum.audioplayer4j.AudioPlayerListener;
import com.tagtraum.audioplayer4j.device.DefaultAudioDevice;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.event.SwingPropertyChangeSupport;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import static java.time.Duration.ZERO;
import static java.time.Duration.of;
import static java.time.temporal.ChronoUnit.MICROS;
import static java.time.temporal.ChronoUnit.NANOS;
import static javax.sound.sampled.AudioFileFormat.Type.AIFF;
import static javax.sound.sampled.AudioFileFormat.Type.WAVE;
import static javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED;
import static javax.sound.sampled.AudioSystem.NOT_SPECIFIED;

/**
 * Plays an audio resource using standard Java APIs.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class JavaPlayer implements AudioPlayer {

    private static final Logger LOG = Logger.getLogger(JavaPlayer.class.getName());
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean MAC = OS_NAME.contains("mac");
    private static final String DURATION = "duration";
    private static final float DEFAULT_BUFFER_SIZE = 0.1f; // in s
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(JavaPlayer.class);
    private static final String JAVAPLAYER_BUFFER = "javaplayer.buffer";
    private static final AtomicInteger id = new AtomicInteger(0);
    private static final int DEFAULT_MIN_TIME_EVENT_DIFFERENCE = 200;
    private static Cleaner cleaner;

    private final PropertyChangeSupport propertyChangeSupport = new SwingPropertyChangeSupport(this, true);
    private final ExecutorService serializer;
    private final List<AudioPlayerListener> audioPlayerListeners = new ArrayList<>();
    private final Cleaner instanceCleaner;

    private float bufferSizeInSeconds = PREFERENCES.getFloat(JAVAPLAYER_BUFFER,
        Float.parseFloat(System.getProperty(JAVAPLAYER_BUFFER, "" + DEFAULT_BUFFER_SIZE)));

    private int minTimeEventDifference = DEFAULT_MIN_TIME_EVENT_DIFFERENCE;
    private URI song;
    private AudioFormat audioFormat;
    private AudioFileFormat audioFileFormat;
    private Duration duration;
    private SourceDataLine line;
    private float volume = 1f;
    private float effectiveVolume = 1f;
    private SingleThreadedAudioInputStream stream;
    private boolean paused = true;
    private Duration seekTime = null;
    private Duration time = null;
    private boolean muted;
    private boolean unstarted;
    private boolean unfinished;
    private AudioDevice audioDevice = DefaultAudioDevice.getInstance();
    private StreamLinePump streamLinePump;
    private float gain;
    private Cleaner.Cleanable streamCleanable;
    private Cleaner.Cleanable lineCleanable;

    /**
     * Create an instance using a private {@link Cleaner} instance.
     * It's recommended to use {@link #JavaPlayer(Cleaner)} instead and provide
     * a library wide cleaner instance.
     */
    public JavaPlayer() {
        this(getLibraryCleaner());
    }

    /**
     * Create an instance using the specified cleaner instance.
     *
     * @param cleaner cleaner instance used to ensure proper clean up when this instance becomes
     *                eligible for garbage collection
     */
    public JavaPlayer(final Cleaner cleaner) {
        this.serializer = java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> new Thread(r, "Player Thread " + id.incrementAndGet())
        );
        this.instanceCleaner = cleaner;
    }

    private static synchronized Cleaner getLibraryCleaner() {
        if (cleaner == null) {
            cleaner = Cleaner.create();
        }
        return cleaner;
    }

    /**
     * Minimum delay in ms between "time" property events.
     *
     * @return time in ms
     */
    public int getMinTimeEventDifference() {
        return minTimeEventDifference;
    }

    /**
     * Minimum delay in ms between "time" property events.
     *
     * @param minTimeEventDifference time in ms
     */
    public void setMinTimeEventDifference(final int minTimeEventDifference) {
        this.minTimeEventDifference = minTimeEventDifference;
    }

    /**
     * Buffer size.
     *
     * @param bufferSizeInSeconds buffer size in seconds
     */
    public void setBufferSizeInSeconds(final float bufferSizeInSeconds) {
        if (bufferSizeInSeconds != this.bufferSizeInSeconds) {
            this.bufferSizeInSeconds = bufferSizeInSeconds;
            PREFERENCES.putFloat(JAVAPLAYER_BUFFER, bufferSizeInSeconds);
        }
    }

    @Override
    public void setAudioDevice(final AudioDevice audioDevice) throws IllegalArgumentException {
        Objects.requireNonNull(audioDevice, "AudioDevice must not be null");
        if (audioDevice.getMixer() == null) {
            throw new IllegalArgumentException("Mixer is null. For " + getClass().getSimpleName() + ", the audio device must provide a mixer: " + audioDevice);
        }
        final AudioDevice oldAudioDevice = this.audioDevice;
        if (!this.audioDevice.equals(audioDevice)) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Attempt to set new AudioDevice " + audioDevice);
                LOG.fine("New device supported lines:\n" + Arrays.toString(audioDevice.getMixer().getSourceLineInfo()));
            }
            try {
                this.audioDevice = audioDevice;
                if (this.audioFileFormat != null) {
                    final boolean startLine = line != null && line.isRunning();
                    openLine();
                    this.streamLinePump = new StreamLinePump(stream, line);
                    this.serializer.submit(streamLinePump);
                    if (startLine) {
                        if (LOG.isLoggable(Level.FINE)) LOG.fine("line.start()");
                        line.start();
                    }
                }
                setVolume(this.volume);
                setMuted(this.muted);
            } catch (Exception e) {
                // revert to old
                this.audioDevice = oldAudioDevice;
                throw new IllegalArgumentException("Failed to open line on new audio device: " + audioDevice, e);
            }
        }
        firePropertyChange("audioDevice", oldAudioDevice, audioDevice);
    }

    @Override
    public AudioDevice getAudioDevice() {
        return audioDevice;
    }

    @Override
    public URI getURI() {
        return song;
    }

    @Override
    public void open(final URI song) throws IOException, UnsupportedAudioFileException {
        if (song == null) {
            LOG.info("Called open with null as argument.");
            close();
            return;
        }
        if (LOG.isLoggable(Level.FINE)) LOG.fine("open(): " + song);
        final URI oldSong = this.song;
        final Duration oldDuration = this.duration;
        final boolean oldPaused = this.paused;
        if (stream != null || line != null) {
            quietClose();
            this.paused = oldPaused;
        }
        try {
            final URL url = song.toURL();
            if (url.toString().toLowerCase().endsWith(".m4p")) {
                throw new UnsupportedAudioFileException("DRM protected content not supported.");
            }
            if (url.toString().startsWith("file:")) {
                final Path file = Paths.get(song);
                if (Files.notExists(file) || !Files.isReadable(file)) throw new FileNotFoundException(file.toString());
            }
            this.audioFileFormat = ExtAudioSystem.getAudioFileFormat(url);
            this.duration = getDuration(audioFileFormat);
            try {
                openLine();
            } catch(IllegalArgumentException e) {
                // we weren't able to create a line/output for this file
                final UnsupportedAudioFileException reE = new UnsupportedAudioFileException(e.getMessage());
                reE.initCause(e);
                throw reE;
            }
            open(url);
            internalSetTime(ZERO, false);
            this.streamLinePump = new StreamLinePump(stream, line);
            this.serializer.submit(streamLinePump);
            this.unstarted = true;
            this.unfinished = true;
            this.song = song;
            if (!paused) {
                play();
            }
        } catch (Exception e) {
            this.paused = oldPaused;
            this.song = null;
            this.audioFileFormat = null;
            this.audioFormat = null;
            this.duration = null;
            if (this.streamCleanable != null) {
                this.streamCleanable.clean();
                this.streamCleanable = null;
            }
            this.stream = null;
            if (e instanceof IOException) {
                throw (IOException)e;
            }
            if (e instanceof UnsupportedAudioFileException) {
                throw (UnsupportedAudioFileException)e;
            }
            throw new IOException(e);
        } finally {
            firePropertyChange("uri", oldSong, this.song);
            firePropertyChange("duration", oldDuration, this.duration);
            propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);
        }
    }

    /**
     * Returns duration in <em>microseconds</em>.
     *
     * @param audioFileFormat audioFileFormat
     * @return duration in microseconds.
     * @see javax.sound.sampled.AudioFileFormat
     */
    private static Duration getDuration(final AudioFileFormat audioFileFormat) {
        if (audioFileFormat.properties().containsKey(DURATION)) {
            return of((Long)audioFileFormat.properties().get(DURATION), MICROS);
        } else if (audioFileFormat.getFormat().getEncoding() == PCM_SIGNED
            && audioFileFormat.getFrameLength() != NOT_SPECIFIED && audioFileFormat.getFormat().getFrameRate() != NOT_SPECIFIED
            && (audioFileFormat.getType() == WAVE
            || audioFileFormat.getType() == AIFF)) {
            return of((long) (audioFileFormat.getFrameLength() / audioFileFormat.getFormat().getFrameRate()  * 1000L * 1000L * 1000L), NANOS);
        }
        return null;
    }


    private void reopen() throws InterruptedException, UnsupportedAudioFileException, LineUnavailableException, ExecutionException, IOException {
        if (LOG.isLoggable(Level.INFO)) LOG.info("Re-opening " + song);
        // ensure line is still open, if not re-open
        if (line != null && !line.isOpen()) {
            openLine();
        }
        final URL url = song.toURL();
        if (url.toString().toLowerCase().endsWith(".m4p")) {
            throw new UnsupportedAudioFileException("DRM protected content not supported.");
        }
        if (url.toString().startsWith("file:")) {
            final Path file = Paths.get(song);
            if (Files.notExists(file) || !Files.isReadable(file)) throw new FileNotFoundException(file.toString());
        }
        open(url);
//        forceInternalSetTime(ZERO);
        this.streamLinePump = new StreamLinePump(stream, line);
        this.serializer.submit(streamLinePump);
    }

    private void open(final URL url) throws UnsupportedAudioFileException, IOException, ExecutionException, InterruptedException {
        if (this.streamCleanable != null) {
            this.streamCleanable.clean();
            this.streamCleanable = null;
        }
        this.stream = null;
        this.stream = new SingleThreadedAudioInputStream(url, this.audioFormat);
        this.streamCleanable = instanceCleaner.register(this, new Destroyer(this.stream));
    }

    private void openLine() throws LineUnavailableException {
        if (LOG.isLoggable(Level.FINE)) LOG.fine("openLine()");
        final AudioFormat format = audioFileFormat.getFormat();
        final AudioFormat pcmFormat = toSignedPCM(format);
        try {
            openLine(pcmFormat);
            this.audioFormat = pcmFormat;
        } catch (Exception e) {
            // fallback format
            final int channels = format.getChannels() > 0 ? format.getChannels() : 2;
            final AudioFormat af = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, channels, 2*channels, 44100f, format.isBigEndian());
            try {
                if (LOG.isLoggable(Level.FINE)) LOG.fine("Line for " + pcmFormat + " is unavailable, falling back to " + af);
                openLine(af);
                this.audioFormat = af;
            } catch (LineUnavailableException | IllegalArgumentException ex) {
                // second fallback (reduce number of channels)
                final int channels2 = channels >= 2 ? 2 : 1;
                final AudioFormat af2 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, channels2, 2*channels2, 44100f, format.isBigEndian());
                if (LOG.isLoggable(Level.FINE)) LOG.fine("Line for " + af + " is unavailable, falling back to " + af2);
                openLine(af2);
                this.audioFormat = af2;
            }
        }
    }

    private void openLine(final AudioFormat desiredFormat) throws LineUnavailableException, IllegalArgumentException {
        final DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, desiredFormat);
        final SourceDataLine line = (SourceDataLine)audioDevice.getLine(lineInfo);
        final int bufferSize = desiredFormat.getFrameSize() *  (int)(desiredFormat.getFrameRate() * this.bufferSizeInSeconds);
        try {
            line.addLineListener(event -> LOG.info("Line: " + event));
            line.open(desiredFormat, bufferSize);
        } catch (LineUnavailableException e) {
            LOG.log(Level.SEVERE, "Failed to open line with format " + desiredFormat +  " and buffer " + bufferSize + " on mixer " + audioDevice, e);
            LOG.info("Retrying with default buffer...");
            try {
                line.open(desiredFormat);
                this.bufferSizeInSeconds = (float)line.getBufferSize() / desiredFormat.getFrameSize() / desiredFormat.getFrameRate();
                LOG.info("Effective buffer size in seconds: " + this.bufferSizeInSeconds);
            } catch (LineUnavailableException e1) {
                LOG.log(Level.SEVERE, "Failed to open line with format " + desiredFormat +  " and default buffer on mixer " + audioDevice, e1);
                LOG.fine("Device-supported lines:\n" + Arrays.toString(audioDevice.getMixer().getSourceLineInfo()));
                throw e1;
            }
        }
        if (line != this.line) {
            if (this.lineCleanable != null) {
                this.lineCleanable.clean();
            }
            this.lineCleanable = instanceCleaner.register(this, new Destroyer(line));
        }
        this.line = line;
        this.line.addLineListener(event -> {
            if (LineEvent.Type.START.equals(event.getType())) {
                // fire started when the playback actually starts
                fireStarted();
            }
        });

        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            LOG.warning("Master gain control not supported by line " + line + " for " + desiredFormat);
        }
        if (!line.isControlSupported(BooleanControl.Type.MUTE)) {
            LOG.warning("Mute control not supported by line " + line + " for " + desiredFormat);
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Requested line buffer: " + bufferSize + ". Obtained: " + this.line.getBufferSize());
        }

        // restore state from when line was not open/didn't exist
        updateMasterGainControl();
        updatedMutedControl();
    }

    private static AudioFormat toSignedPCM(final AudioFormat format) {
        final int sampleSizeInBits = format.getSampleSizeInBits() <= 0 ? 16 : format.getSampleSizeInBits();
        final int channels = format.getChannels() <= 0 ? 2 : format.getChannels();
        final float sampleRate = format.getSampleRate() <= 0 ? 44100f : format.getSampleRate();
        final boolean bigEndian = MAC ? false : format.isBigEndian();
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            sampleSizeInBits,
            channels,
            (sampleSizeInBits > 0 && channels > 0) ? (sampleSizeInBits/8)*channels : AudioSystem.NOT_SPECIFIED,
            sampleRate,
            bigEndian
        );
    }

    @Override
    public void playPause() {
        if (paused) play();
        else pause();
    }

    @Override
    public void play() {
        if (audioFileFormat == null || stream == null || line == null) {
            throw new IllegalStateException("Player wasn't opened successfully.");
        }
        final boolean oldPaused = this.paused;
        this.paused = false;
        if (LOG.isLoggable(Level.FINE)) LOG.fine("play(): line.start()");
        line.start();
        this.propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);
    }

    @Override
    public void pause() {
        if (line == null) {
            LOG.warning("Call to pause() even though player is not open.");
            return;
        }
        final boolean oldPaused = this.paused;
        this.paused = true;
        if (LOG.isLoggable(Level.FINE)) LOG.fine("pause(): line.stop()");
        line.stop();
        propertyChangeSupport.firePropertyChange("paused", oldPaused, paused);
    }

    @Override
    public void close() {
        final URI oldSong = song;
        final Duration oldDuration = duration;
        final boolean oldPaused = paused;
        quietClose();
        internalSetTime(null, false);
        this.paused = true;
        firePropertyChange("uri", oldSong, this.song);
        firePropertyChange("duration", oldDuration, this.duration);
        propertyChangeSupport.firePropertyChange("paused", oldPaused, this.paused);
    }

    /**
     * Closes player without sending out property change events.
     */
    private void quietClose() {
        if (LOG.isLoggable(Level.FINE)) LOG.fine("quietClose()");
        fireFinished();
        if (this.streamCleanable != null) {
            this.streamCleanable.clean();
            this.streamCleanable = null;
        }
        this.stream = null;
        this.song = null;
        this.audioFileFormat = null;
        this.audioFormat = null;
        this.duration = null;
        this.paused = false;

        if (this.lineCleanable != null) {
            this.lineCleanable.clean();
            this.lineCleanable = null;
        }
        this.line = null;
    }

    @Override
    public void reset() {
        final boolean oldPaused = paused;
        this.paused = true;
        if (line != null) {
            if (LOG.isLoggable(Level.FINE)) LOG.fine("line.close()");
            line.close();
        }
        this.serializer.submit(() -> {
            if (line != null) {
                if (LOG.isLoggable(Level.FINE)) LOG.fine("line.flush()");
                line.flush();
            }
            if (this.streamCleanable != null) {
                this.streamCleanable.clean();
                this.streamCleanable = null;
            }
            this.stream = null;

            if (song != null) {
                try {
                    open(song);
                    if (!oldPaused) {
                        play();
                    }
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e.toString(), e);
                }
            } else {
                LOG.warning("Call to reset() even though no song is open.");
            }
        });
    }

    @Override
    public Duration getDuration() {
        return duration;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void setVolume(final float volume) {
        final float oldVolume = this.volume;
        final float oldEffectiveVolume = this.effectiveVolume;
        this.volume = volume;
        if (muted && volume > 0f) {
            setMuted(false);
        }
        if (line != null) {
            updateMasterGainControl();
        } else {
            updateEffectiveVolumeWithoutLine();
        }
        propertyChangeSupport.firePropertyChange("volume", oldVolume, volume);
        propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
    }

    private void updateEffectiveVolumeWithoutLine() {
        float dB = AudioPlayer.volumeToGain(this.volume);
        dB += gain;
        this.effectiveVolume = AudioPlayer.gainToVolume(dB);
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
        if (line != null) {
            updateMasterGainControl();
        } else {
            updateEffectiveVolumeWithoutLine();
        }
        propertyChangeSupport.firePropertyChange("gain", oldGain, gain);
        propertyChangeSupport.firePropertyChange("effectiveVolume", oldEffectiveVolume, effectiveVolume);
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
    public void setMuted(final boolean muted) {
        final boolean oldMuted = this.muted;
        this.muted = muted;
        if (line != null) {
            updatedMutedControl();
        } else {
            updateEffectiveVolumeWithoutLine();
        }
        propertyChangeSupport.firePropertyChange("muted", oldMuted, muted);
    }

    @Override
    public boolean isMuted() {
        return this.muted;
    }

    private void updatedMutedControl() {
        if (line.isControlSupported(BooleanControl.Type.MUTE)) {
            final BooleanControl muteControl = (BooleanControl) line.getControl(BooleanControl.Type.MUTE);
            try {
                muteControl.setValue(this.muted);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            }
        } else {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                final FloatControl masterGainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                try {
                    masterGainControl.setValue(AudioPlayer.volumeToGain(0));
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e.toString(), e);
                }
            }
        }
    }

    private void updateMasterGainControl() {
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            final FloatControl masterGainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            try {
                float dB = AudioPlayer.volumeToGain(this.volume);
                dB += gain;
                if (dB > masterGainControl.getMaximum()) dB = masterGainControl.getMaximum();
                if (dB < masterGainControl.getMinimum()) dB = masterGainControl.getMinimum();
                masterGainControl.setValue(dB);
                this.effectiveVolume = AudioPlayer.gainToVolume(dB);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            }
        }
    }

    @Override
    public Duration getTime() {
        if (audioFormat == null || stream == null || streamLinePump == null) {
            return null;
        } else {
            return streamLinePump.getTime();
        }
    }

    @Override
    public void setTime(final Duration time) {
        Objects.requireNonNull(time, "Cannot set time to null");
        if (time.isNegative()) throw new IllegalArgumentException("Time must not be less than zero: " + time);
        if (audioFormat == null || stream == null || streamLinePump == null) throw new IllegalStateException("No resource loaded");
        if (duration != null && time.compareTo(duration) > 0) {
            throw new IllegalArgumentException("Time (" + time + ") must not be greater than duration (" + duration + ")");
        }
        this.setSeekTime(time);
    }

    private synchronized Duration getSeekTime() {
        return seekTime;
    }

    private synchronized void setSeekTime(final Duration seekTime) {
        final Duration oldSeekTime = this.seekTime;
        this.seekTime = seekTime;
        if (seekTime != null && !seekTime.equals(oldSeekTime) && line.isOpen()) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("New seekTime=" + seekTime + " - line.flush()");
            }
            line.flush();
        }
    }

    private synchronized void resetSeekTime() {
        setSeekTime(null);
    }

    private synchronized void internalSetTime(final Duration time, final boolean forceFire) {
        final Duration oldTime = this.time;
        if (this.time == null || time == null) {

            if (oldTime != time) {
                LOG.log(Level.INFO, "internalSetTime(" + time + ", force=" + forceFire + ")", new RuntimeException());
            }

            this.time = time;
            firePropertyChange("time", oldTime, time);
        } else {
            // don't fire more often than once every x milliseconds (minTimeEventDifference)
            final long diff = time.minus(this.time).toMillis();
            if (forceFire || diff > minTimeEventDifference || diff < 0) {

                if (!oldTime.equals(time)) {
                    LOG.log(Level.INFO, "internalSetTime(" + time + ", force=" + forceFire + ")", new RuntimeException());
                }

                this.time = time;
                firePropertyChange("time", oldTime, time);
            }
        }
    }

    private void fireStarted() {
        if (unstarted) {
            unstarted = false;
            final URI s = song;
            SwingUtilities.invokeLater(() -> {
                for (final AudioPlayerListener listener : audioPlayerListeners) {
                    listener.started(JavaPlayer.this, s);
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
                    listener.finished(JavaPlayer.this, s);
                }
            });
        }
    }

    private void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
        // do not fire, if both are null
        if (oldValue != null || newValue != null) {
            propertyChangeSupport.firePropertyChange(propertyName, oldValue, newValue);
        }
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

    @Override
    public String toString() {
        return "JavaPlayer{" +
            "song=" + song +
            ", duration=" + duration +
            ", audioFormat=" + audioFormat +
            ", volume=" + volume +
            ", paused=" + paused +
            ", stream=" + stream +
            ", bufferSizeInSeconds=" + bufferSizeInSeconds +
            '}';
    }

    private static class Volume24Bit {

        /**
         * A constant holding the minimum value a <code>signed24bit</code> can
         * have, -2<sup>22</sup>.
         */
        private static final int MIN_VALUE_24BIT = -2 << 22;

        /**
         * A constant holding the maximum value a <code>signed24bit</code> can
         * have, 2<sup>22</sup>-1.
         */
        private static final int MAX_VALUE_24BIT = -MIN_VALUE_24BIT-1;

        public void filter(final byte[] buffer, final AudioFormat format, final float volume) {
            final int bytesPerChannel = format.getFrameSize() / format.getChannels();
            for (int frame = 0; frame<buffer.length/bytesPerChannel; frame++) {
                final int sample = byteToSample(buffer, bytesPerChannel, frame);
                int scaledSample = (int)(sample*volume);
                if (scaledSample > MAX_VALUE_24BIT) scaledSample = MAX_VALUE_24BIT;
                else if (scaledSample < MIN_VALUE_24BIT) scaledSample = MIN_VALUE_24BIT;
                writeSample(scaledSample, buffer, bytesPerChannel, frame);
            }
        }

        private int byteToSample(final byte[] bytes, final int bytesPerChannel, final int sampleOffset) {
            int sample = 0;
            for (int byteIndex=0; byteIndex<bytesPerChannel; byteIndex++) {
                final int aByte = bytes[sampleOffset*bytesPerChannel+byteIndex] & 0xff;
                //sample += aByte << (8*(bytesPerChannel-byteIndex-1));

                // little endian:
                sample += aByte << (8*byteIndex);
            }
            return sample > MAX_VALUE_24BIT ? sample + MIN_VALUE_24BIT + MIN_VALUE_24BIT : sample;
        }

        private void writeSample(final int sample, final byte[] bytes, final int bytesPerChannel, final int sampleOffset) {
            for (int byteIndex=0; byteIndex<bytesPerChannel; byteIndex++) {
                //final int shift = 8 * (bytesPerChannel - byteIndex - 1);

                // little endian:
                final int shift = 8 * byteIndex;
                bytes[sampleOffset*bytesPerChannel+byteIndex] = (byte)(sample >>> shift);
            }
        }
    }


    /**
     * Pumps data from a stream to the line in a run() loop.
     * The loop automatically breaks, if the line is closed or the stream
     * has ended.
     */
    private class StreamLinePump implements Runnable, LineListener {

        private final SourceDataLine line;
        private final boolean useCustomGainControl;
        private final SingleThreadedAudioInputStream stream;
        private long lineTimeDiff; // TODO: Use frames instead of timestamps
        private boolean stopped;

        private StreamLinePump(final SingleThreadedAudioInputStream stream, final SourceDataLine line) {
            if (!line.isOpen()) throw new IllegalStateException("Line must be open, but isn't: " + line);
            this.line = line;
            this.useCustomGainControl = line.getFormat().getSampleSizeInBits() == 24
                && !line.isControlSupported(FloatControl.Type.MASTER_GAIN);
            this.stream = stream;
            this.line.addLineListener(this);
            markLineTimeDiff(getStreamTime());
        }

        public synchronized void stop() {
            this.stopped = true;
            this.notify();
        }

        @Override
        public void update(final LineEvent event) {
            if (LOG.isLoggable(Level.FINE)) LOG.fine("LineEvent: " + event);
            synchronized (this) {
                this.notifyAll();
            }
        }

        public Duration getTime() {
            synchronized (JavaPlayer.this) {
                final Duration time;
                final Duration seekTime = getSeekTime();
                if (seekTime != null) {
                    time = seekTime;
                    if (LOG.isLoggable(Level.FINEST)) {
                        LOG.finest("getTime(), using seekTime: " + time);
                    }
                } else if (line != null && line.isOpen()) {
                    time = of(line.getMicrosecondPosition() - lineTimeDiff, MICROS);
                    if (LOG.isLoggable(Level.INFO)) {
                        LOG.info("getTime(), using line time: " + time);
                        LOG.info("line.getMicrosecondPosition(): " + line.getMicrosecondPosition());
                        LOG.info("lineTimeDiff: " + lineTimeDiff);
                    }
                } else {
                    time = getStreamTime();
                    if (LOG.isLoggable(Level.FINEST)) {
                        LOG.finest("getTime(), using getStreamTime(): " + time);
                    }
                }
                LOG.info("time=" + time + ", seekTime=" + seekTime + ", line.isOpen=" + (line != null && line.isOpen()) + ", streamTime=" + getStreamTime());
                return time;
            }
        }

        private Duration getStreamTime() {
            if (stream == null || audioFormat == null) return null;
            else return of((long) (stream.getFrameNumber() / audioFormat.getSampleRate() * 1000000), MICROS);
        }

        private Duration getBufferTime(final int diffToStreamInBytes) {
            if (stream == null || audioFormat == null) return null;
            else {
                final int diffToStreamInFrames = diffToStreamInBytes / audioFormat.getFrameSize();
                return of((long) ((stream.getFrameNumber()-diffToStreamInFrames) / audioFormat.getSampleRate() * 1000000), MICROS);
            }
        }

        @Override
        public void run() {
            markLineTimeDiff(getStreamTime());
            final AudioFormat lineFormat = line.getFormat();
            final int bytesPerSecond = lineFormat.getFrameSize() * (int)lineFormat.getSampleRate();
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Line data rate in bytes/s: " + bytesPerSecond);
                LOG.fine("Required buffer size for 10s: " + (bytesPerSecond*10));
            }
            final byte[] buf = new byte[10 * bytesPerSecond];
            int justRead = 0;
            try {
                while (line.isOpen()) {
                    if (stopped) throw new InterruptedException("Stopping " + this);
                    // seek with seek()
                    seekWithSeekableStream();

                    if (stopped) throw new InterruptedException("Stopping " + this);

                    final Duration seekTime = getSeekTime();
                    final Duration streamTime = getStreamTime();
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("seekTime=" + seekTime + ", streamTime=" + streamTime);
                    }

                    if (seekTime == null || streamTime == null) {
                        if (LOG.isLoggable(Level.INFO)) {
                            LOG.info("Reading, NOT seeking");
                        }
                        // regular read (no seeking)
                        justRead = stream.read(buf);
                        if (justRead < 0) {
                            if (LOG.isLoggable(Level.INFO)) {
                                LOG.info("Stream has ended.");
                            }
                            if (seekTime == null) {
                                // stream has ended
                                if (LOG.isLoggable(Level.INFO)) {
                                    LOG.info("line.drain()");
                                }
                                line.drain();
                                if (stopped) throw new InterruptedException("Stopping " + this);
                                quietClose();
                                break;
                            } else {
                                // we are still seeking and should not simply
                                // close the stream...
                                // instead, pretend we have read nothing
                                justRead = 0;
                            }
                        }
                        internalSetTime(getTime(), false);
                    } else {
                        if (LOG.isLoggable(Level.INFO)) {
                            LOG.info("Seeking, NOT reading");
                        }
                        // we are in seek mode

                        final Duration bufferTime = getBufferTime(justRead);
                        if (justRead > 0 && bufferTime != null && seekTime.compareTo(bufferTime) >= 0) {
                            if (LOG.isLoggable(Level.INFO)) {
                                LOG.info("seekTime >= buffer(Start)Time: Skipping ahead in buffer");
                            }
                            final int bytesToSkip = (int)getBytesToSkip(lineFormat, seekTime, bufferTime);
                            // we have already read audio we want to play
                            // lets drop the parts we don't want to play
                            System.arraycopy(buf, bytesToSkip, buf, 0, justRead- bytesToSkip);
                            justRead = justRead - bytesToSkip;
                            reachedSeekTime(seekTime);
                        } else if (seekTime.compareTo(streamTime) >= 0) {
                            // keep on reading, until we reach seekTime
                            if (LOG.isLoggable(Level.INFO)) {
                                LOG.info("seekTime >= streamTime: Skipping ahead in stream");
                            }
                            long bytesStillToSkip = getBytesToSkip(lineFormat, seekTime, streamTime);
                            justRead = 0;
                            while (bytesStillToSkip > 0) {
                                justRead = stream.read(buf);
                                if (justRead < 0) {
                                    // stream end - seek time is unreachable
                                    quietClose();
                                    return;
                                } else {
                                    if (justRead > bytesStillToSkip) {
                                        // we have already read audio we want to play
                                        // lets drop the parts we don't want to play
                                        System.arraycopy(buf, (int)bytesStillToSkip, buf, 0, justRead-(int)bytesStillToSkip);
                                        justRead = justRead - (int)bytesStillToSkip;
                                        bytesStillToSkip = 0;
                                    } else {
                                        bytesStillToSkip -= justRead;
                                    }
                                }
                            }
                            reachedSeekTime(seekTime);
                        } else {
                            // we've already read past seekTime: we need to re-open the stream
                            if (LOG.isLoggable(Level.FINE)) {
                                LOG.fine("seekTime < streamTime: re-open stream");
                            }
                            try {
                                reopen();
                            } catch (UnsupportedAudioFileException | LineUnavailableException | ExecutionException e) {
                                LOG.log(Level.SEVERE, "Failed to seek, re-open " + getURI(), e);
                            }
                            return;
                        }
                    }

                    if (LOG.isLoggable(Level.INFO)) {
                        LOG.info("justRead: " + justRead);
                    }

                    // workaround missing controls for 24 bit audio
                    if (useCustomGainControl) {
                        adjustVolume(buf);
                    }
                    writeToLine(line, buf, justRead);
                }
                resetSeekTime();
            } catch (IOException e) {
                resetSeekTime();
                LOG.log(Level.SEVERE, "An IOException occurred: " + e, e);
            } catch (InterruptedException e) {
                if (LOG.isLoggable(Level.FINE)) LOG.log(Level.FINE, "Pump was stopped: " + this, e);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "A RuntimeException occurred: " + e, e);
                throw e;
            }
        }

        private void reachedSeekTime(final Duration seekTime) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("Reached seekTime " + seekTime);
            }
            markLineTimeDiff(seekTime);
            resetSeekTime();
            // force fire
            internalSetTime(getTime(), true);
        }

        private long getBytesToSkip(final AudioFormat lineFormat, final Duration seekTime, final Duration currentTime) {
            final Duration timeToSkip = seekTime.minus(currentTime);
            long bytesStillToSkip;
            try {
                bytesStillToSkip = ((long) (timeToSkip.toNanos() * lineFormat.getSampleRate() / (1000L * 1000L * 1000L))) * lineFormat.getFrameSize();
            } catch (ArithmeticException e) {
                bytesStillToSkip = ((long) (timeToSkip.toMillis() * lineFormat.getSampleRate() / 1000L)) * lineFormat.getFrameSize();
            }
            return bytesStillToSkip;
        }

        /**
         * Write data from buffer to line (to play).
         *
         * @param line line
         * @param buf audio data buffer
         * @param length data length
         * @throws InterruptedException if the pump is stopped
         */
        private void writeToLine(final SourceDataLine line, final byte[] buf, final int length) throws InterruptedException {
            final AudioFormat lineFormat = line.getFormat();
            int pos = 0;
            while (pos < length && line.isOpen()) {
                if (LOG.isLoggable(Level.INFO)) {
                    LOG.info("Wrote " + pos + "/" + length + " bytes");
                }
                if (stopped) throw new InterruptedException("Stopping " + this);
                final int available = line.available();
                final int bufferSize = line.getBufferSize();
                final int chunkLength;

                // determine how many bytes we actually want to write in one go
                if (available == 0) {
                    LOG.warning("Probably cannot write to line without blocking, available == 0. Trying to write 0.1 second worth of data.");
                    final int tenthSecondInBytes = (int)(lineFormat.getSampleRate() / 10f) * lineFormat.getFrameSize();
                    chunkLength = Math.min(tenthSecondInBytes, length - pos);
                } else {
                    chunkLength = Math.min(available, length - pos);
                }

                // adjust buffer
                if (available == bufferSize && pos != 0) {
                    final AudioFormat format = line.getFormat();
                    // increase to at most 1s
                    final float bufferSizeInSeconds = Math.min(1f, bufferSize * 2f / (format.getFrameSize() * format.getSampleRate()));
                    JavaPlayer.this.setBufferSizeInSeconds(bufferSizeInSeconds);
                    LOG.warning("Looks like we have a buffer underrun. Doubling buffer length for NEXT line to " + bufferSizeInSeconds + "s");
                }

                if (LOG.isLoggable(Level.INFO)) {
                    LOG.info("Attempt at writing " + chunkLength + " bytes to line... (line.isActive=" + line.isActive() + ", line.isRunning=" + line.isRunning() + ")");
                }
                final int written = line.write(buf, pos, chunkLength);
                pos += written;
                if (LOG.isLoggable(Level.INFO)) {
                    LOG.info("Written: " + written + " bytes");
                }
                // break out of write-loop for seeking
                if (getSeekTime() != null) {
                    break;
                }
                internalSetTime(getTime(), false);
            }
            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("End of write loop for " + length + " bytes. Wrote " + pos + " bytes");
            }
        }

        /**
         * Custom volume adjustment, in case the control is missing (e.g. for 24 bit audio).
         *
         * @param buf audio data
         */
        private void adjustVolume(final byte[] buf) {
            float dB = AudioPlayer.volumeToGain(volume);
            dB += gain;
            final float adjustedVolume = Math.max(0, AudioPlayer.gainToVolume(dB));

            final Volume24Bit filter = new Volume24Bit();
            if (muted) {
                filter.filter(buf, line.getFormat(), 0);
            } else {
                filter.filter(buf, line.getFormat(), adjustedVolume);
            }
        }

        /**
         * Seek time position using the {@link SingleThreadedAudioInputStream#seek(Duration)}
         * method.
         */
        private void seekWithSeekableStream() throws InterruptedException {
            final Duration seekTime = getSeekTime();
            if (seekTime != null) {
                try {
                    final boolean seekable = stream.isSeekable();
                    if (seekable) {
                        stream.seek(seekTime);
                        markLineTimeDiff(seekTime);
                        resetSeekTime();
                    } else {
                        if (LOG.isLoggable(Level.FINE)) LOG.fine("Seek not supported.");
                    }
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to seek to time " + seekTime + "ms. We assume the stream needs to be re-opened.", e);
                    try {
                        JavaPlayer.this.reopen();
                    } catch (UnsupportedAudioFileException | LineUnavailableException | ExecutionException | IOException e1) {
                        LOG.log(Level.SEVERE, "Failed to re-open " + song, e1);
                    }
                    this.stopped = true;
                    final InterruptedException interruptedException = new InterruptedException("Seek failed with " + e );
                    interruptedException.initCause(e);
                    throw interruptedException;
                }
            }
        }

        private void markLineTimeDiff(final Duration time) {
            synchronized (JavaPlayer.this) {
                lineTimeDiff = line.getMicrosecondPosition() - time.dividedBy(MICROS.getDuration());
                if (LOG.isLoggable(Level.INFO)) {
                    LOG.info("New line time diff: " + lineTimeDiff + " micros");
                }
            }
        }
    }

    /**
     * Hook that ensures resources are eventually freed.
     * This is the Java 9 equivalent of the deprecated {@link #finalize()}.
     */
    private static class Destroyer implements Runnable {

        private final AutoCloseable autoCloseable;

        public Destroyer(final AutoCloseable autoCloseable) {
            Objects.requireNonNull(autoCloseable);
            this.autoCloseable = autoCloseable;
        }

        @Override
        public void run() {
            LOG.info("Closing " + autoCloseable);
            try {
                if (autoCloseable instanceof Line) {
                    if (((Line) autoCloseable).isOpen())
                        autoCloseable.close();
                } else {
                    autoCloseable.close();
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to close " + autoCloseable, e);
            }
        }
    }

}
