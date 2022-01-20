/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import com.tagtraum.audioplayer4j.java.JavaPlayer;
import com.tagtraum.audioplayer4j.javafx.JavaFXPlayer;
import com.tagtraum.audioplayer4j.macos.AVFoundationPlayer;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for {@link AudioPlayer} instances.
 * To get an instance, use {@link AudioPlayerFactory#open(URI)} or,
 * if playback on a specific {@link AudioDevice} is desired,
 * use {@link AudioPlayerFactory#open(URI, AudioDevice)}.<br>
 * 
 * You may disable certain implementations by calling
 * {@link #setJavaEnabled(boolean)}, {@link #setJavaFXEnabled(boolean)},
 * or {@link #setNativeEnabled(boolean)}.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class AudioPlayerFactory {

    private static final Logger LOG = Logger.getLogger(AudioPlayerFactory.class.getName());
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    private static final Cleaner CLEANER = Cleaner.create();
    private static Boolean JAVA_FX;

    private static boolean nativeEnabled = true;
    private static boolean javaEnabled = true;
    private static boolean javaFXEnabled = true;

    private AudioPlayerFactory() {
    }

    /**
     * Indicates whether a native implementation is enabled.
     *
     * @return true or false
     */
    public static boolean isNativeEnabled() {
        return nativeEnabled;
    }

    /**
     * Enables/disables the native implementation (if available at all).
     *
     * @param nativeEnabled true or false
     */
    public static void setNativeEnabled(final boolean nativeEnabled) {
        AudioPlayerFactory.nativeEnabled = nativeEnabled;
    }

    /**
     * Indicates whether a Java-based implementation is enabled.
     *
     * @return true or false
     */
    public static boolean isJavaEnabled() {
        return javaEnabled;
    }

    /**
     * Enables/disables the Java-based implementation.
     *
     * @param javaEnabled true or false
     */
    public static void setJavaEnabled(final boolean javaEnabled) {
        AudioPlayerFactory.javaEnabled = javaEnabled;
    }

    /**
     * Indicates whether a JavaFX-based implementation is enabled.
     *
     * @return true or false
     */
    public static boolean isJavaFXEnabled() {
        return javaFXEnabled;
    }

    /**
     * Enables/disables the JavaFX-based implementation (if available at all).
     *
     * @param javaFXEnabled true or false
     */
    public static void setJavaFXEnabled(final boolean javaFXEnabled) {
        AudioPlayerFactory.javaFXEnabled = javaFXEnabled;
    }

    /**
     * Opens an {@link AudioPlayer} instance suitable for the given URI using
     * the default audio device for playback.
     *
     * @param uri audio resource URI
     * @return audio player instance
     * @throws IOException if the URI cannot be opened
     * @see #open(URI, AudioDevice)
     */
    public static AudioPlayer open(final URI uri) throws IOException, UnsupportedAudioFileException {
        return open(uri, null);
    }

    /**
     * Opens an {@link AudioPlayer} instance suitable for the given URI using
     * the given audio device for playback.
     *
     * @param uri audio resource URI
     * @param audioDevice desired audio device
     * @return audio player instance
     * @throws IOException if the URI cannot be opened
     * @see #open(URI)
     */
    public static AudioPlayer open(final URI uri, final AudioDevice audioDevice) throws IOException, UnsupportedAudioFileException {
        Exception lastException = null;

        // prefer AVFoundationPlayer on macOS, because it probably
        // uses the least system resources
        if (MAC && isNativeEnabled()) {
            try {
                final AudioPlayer audioPlayer = new AVFoundationPlayer(CLEANER);
                if (audioDevice != null) {
                    audioPlayer.setAudioDevice(audioDevice);
                }
                audioPlayer.open(uri);
                return audioPlayer;
            } catch (Exception e) {
                LOG.log(Level.INFO, "Failed to open with " + AVFoundationPlayer.class.getSimpleName() + ": " + uri, e);
                lastException = e;
            }
        }

        // Java is always available, but can it play the desired resource?
        if (isJavaEnabled()) {
            try {
                final AudioPlayer audioPlayer = new JavaPlayer(CLEANER);
                if (audioDevice != null) {
                    audioPlayer.setAudioDevice(audioDevice);
                }
                audioPlayer.open(uri);
                return audioPlayer;
            } catch (Exception e) {
                LOG.log(Level.INFO, "Failed to open with " + JavaPlayer.class.getSimpleName() + ": " + uri, e);
                lastException = e;
            }
        }

        // last fallback, if JavaFX is available
        if (isJavaFXEnabled() && isJavaFXAvailable()) {
            try {
                final AudioPlayer audioPlayer = new JavaFXPlayer();
                if (audioDevice != null) {
                    audioPlayer.setAudioDevice(audioDevice);
                }
                audioPlayer.open(uri);
                return audioPlayer;
            } catch (Exception e) {
                LOG.log(Level.INFO, "Failed to open with " + JavaFXPlayer.class.getSimpleName() + ": " + uri, e);
                lastException = e;
            }
        }

        if (lastException instanceof UnsupportedAudioFileException) {
            throw (UnsupportedAudioFileException) lastException;
        } else if (lastException instanceof IOException) {
            throw (IOException) lastException;
        } else if (lastException != null) throw (RuntimeException) lastException;
        return null;
    }

    /**
     * Test, if the JavaFX MediaPlayer is available.
     *
     * @return true or false
     */
    private static synchronized boolean isJavaFXAvailable() {
        if (JAVA_FX != null) {
            return JAVA_FX;
        }
        boolean available = false;
        try {
            Class.forName("javafx.scene.media.MediaPlayer");
            available = true;
        } catch (Throwable e) {
            LOG.log(Level.INFO, "JavaFX MediaPlayer is not available. Correct modules installed?", e);
        }
        JAVA_FX = available;
        return JAVA_FX;
    }

}
