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
     * Default minimum delay in ms between "time" property events.
     * 
     * @see #getMinTimeEventDifference()
     */
    int DEFAULT_MIN_TIME_EVENT_DIFFERENCE = 200;

    /**
     * Opens the audio resource with the given URI.
     * In order to fully exploit different capabilities of
     * different implementations, you might rather want to
     * use {@link AudioPlayerFactory#open(URI)} instead.
     *
     * @param uri audio resource URI
     * @throws UnsupportedAudioFileException if the stream does not point to
     *         valid audio file data recognized by the system
     * @throws IOException if an input/output exception occurs
     * @see AudioPlayerFactory#open(URI)
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
     *
     * @throws IllegalStateException if no resource is loaded
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
     * If this player is currently muted, it is automatically
     * unmuted, if the new volume != 0f.
     *
     * @param volume volume
     * @throws IllegalArgumentException if the volume is less than 0f or greater than 1f
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
     * Minimum delay in ms between "time" property events.
     * This is not necessarily a precise, binding value. Instead,
     * it is treated by the implementing player class as a
     * non-binding user-wish.
     *
     * @return time in milliseconds
     */
    int getMinTimeEventDifference();

    /**
     * Minimum delay in ms between "time" property events.
     *
     * @param minTimeEventDifference time in milliseconds
     * @see #getMinTimeEventDifference()
     */
    void setMinTimeEventDifference(int minTimeEventDifference);

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

    /**
     * Add a property change listener.
     *
     * @param propertyChangeListener listener
     */
    void addPropertyChangeListener(PropertyChangeListener propertyChangeListener);

    /**
     * Remove a property change listener.
     *
     * @param propertyChangeListener listener
     */
    void removePropertyChangeListener(PropertyChangeListener propertyChangeListener);

    /**
     * Add a property change listener for a given property.
     *
     * @param propertyName property name
     * @param propertyChangeListener listener
     */
    void addPropertyChangeListener(String propertyName, PropertyChangeListener propertyChangeListener);

    /**
     * Remove a property change listener for a given property.
     *
     * @param propertyName property name
     * @param propertyChangeListener listener
     */
    void removePropertyChangeListener(String propertyName, PropertyChangeListener propertyChangeListener);

    /**
     * Add an {@link AudioPlayerListener}.
     *
     * @param listener listener
     */
    void addAudioPlayerListener(AudioPlayerListener listener);

    /**
     * Remove an {@link AudioPlayerListener}.
     *
     * @param listener listener
     */
    void removeAudioPlayerListener(AudioPlayerListener listener);

    @Override
    void close() throws IOException;

}
