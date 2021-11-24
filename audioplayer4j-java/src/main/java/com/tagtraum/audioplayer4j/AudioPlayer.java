/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Audio player.
 *
 * The player's state may be observed via {@link PropertyChangeListener}s,
 * the played resource via an {@link AudioPlayerListener}.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public interface AudioPlayer extends AutoCloseable {

    /**
     * Opens the audio resource with the given URI.
     *
     * @param uri audio resource URI
     * @throws UnsupportedAudioFileException if the stream does not point to
     *         valid audio file data recognized by the system
     * @throws IOException if an input/output exception occurs
     */
    void open(URI uri) throws UnsupportedAudioFileException, IOException;

    /**
     * URI of the audio resource to be played. May be {@code null}.
     *
     * @return uri, may be {@code null}
     */
    URI getURI();

    /**
     * Start playback of the loaded resource.
     *
     * @throws IllegalStateException if no resource is loaded
     * @see #isPaused()
     */
    void play() throws IllegalStateException;

    /**
     * Pause playback of the loaded resource.
     *
     * @throws IllegalStateException if no resource is loaded
     * @see #isPaused()
     */
    void pause() throws IllegalStateException;

    /**
     * Indicates whether the player is the <em>paused</em> state,
     * i.e. it's not playing anything.
     *
     * Note that this method may return {@code true}, even if no resource is loaded.
     *
     * @return true or false
     */
    boolean isPaused();

    /**
     * Toggle playback, i.e. pause if the player is currently playing
     * and start playback, if it's currently paused.
     *
     * @throws IllegalStateException if no resource is loaded
     */
    void playPause() throws IllegalStateException;

    /**
     * Reset playback to the beginning of the resource.
     */
    void reset();

    /**
     * Get current time.
     *
     * @return time
     * @see #setTime(Duration)
     */
    Duration getTime();

    /**
     * Ask the player to seek the desired time.
     * <p>
     * Note that this method returns immediately, which does not mean that
     * the player already reached the desired time.
     * Concretely, {@link #getTime()} may return a different value, when called
     * immediately afterwards.
     *
     * @param time timestamp you wish to seek
     * @throws NullPointerException if time is {@code null}
     * @throws IllegalArgumentException if time is negative
     * @throws IllegalStateException if no resource is loaded
     */
    void setTime(Duration time);

    /**
     * Get duration of the loaded resource.
     *
     * @return duration or {@code null} if no resource is
     *  loaded or the duration is unknown
     */
    Duration getDuration();

    /**
     * Get volume on a linear scale of 0.0-1.0.
     *
     * @return volume
     */
    float getVolume();

    /**
     * Set volume on a linear scale of 0.0-1.0.
     *
     * @param volume volume
     */
    void setVolume(float volume);

    /**
     * Effective volume after application of gain (mute is ignored).
     *
     * @return effective volume
     * @see #getGain()
     * @see #getVolume()
     */
    float getEffectiveVolume();

    /**
     * Get the currently set gain (in dB).
     * The gain may be used to implement things like ReplayGain.
     *
     * @return gain in dB
     */
    float getGain();

    /**
     * Sets the gain.
     * This might be used to implement volume normalization schemes like ReplayGain.
     *
     * @param gain gain in dB
     */
    void setGain(float gain);

    /**
     * Indicates whether this player is currently muted
     *
     * @return true or false
     */
    boolean isMuted();

    /**
     * Turns muting on or off.
     *
     * @param muted true or false
     */
    void setMuted(boolean muted);

    /**
     * Sets the desired audio device.
     *
     * @param audioDevice audio device
     * @throws java.lang.IllegalArgumentException if the device is not supported by this player
     */
    void setAudioDevice(AudioDevice audioDevice) throws IllegalArgumentException;

    /**
     * Current audio device.
     *
     * @return currently used audio device
     */
    AudioDevice getAudioDevice();

    /**
     * Convert dB gain to linear volume in the range 0.0-1.0.
     * This means, too large a gain value may be clipped.
     *
     * @param gain gain in dB
     * @return linear volume
     */
    static float gainToVolume(final float gain) {
        final float volume = (float) Math.pow(10, gain / 20.0);
        if (volume > 1f) return 1f;
        return Math.max(volume, 0f);
    }

    /**
     * Convert linear volume to db gain.
     *
     * @param volume linear volume
     * @return gain in dB
     */
    static float volumeToGain(final float volume) {
        return (float) (Math.log10(volume == 0.0 ? 0.0001 : volume) * 20.0);
    }

    void addPropertyChangeListener(PropertyChangeListener propertyChangeListener);

    void removePropertyChangeListener(PropertyChangeListener propertyChangeListener);

    void addPropertyChangeListener(String propertyName, PropertyChangeListener propertyChangeListener);

    void removePropertyChangeListener(String propertyName, PropertyChangeListener propertyChangeListener);

    void addAudioPlayerListener(AudioPlayerListener listener);

    void removeAudioPlayerListener(AudioPlayerListener listener);

    @Override
    void close() throws IOException;

}
