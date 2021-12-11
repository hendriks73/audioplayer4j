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
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link AudioPlayer} factory.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class AudioPlayerFactory {

    private static final Logger LOG = Logger.getLogger(AudioPlayerFactory.class.getName());
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    private static Boolean JAVA_FX;

    private AudioPlayerFactory() {
    }

    /**
     * Opens an {@link AudioPlayer} instance suitable for the given URI using
     * the default audio device for playback.
     *
     * @param uri audio resource URI
     * @return audio player instance
     * @throws IOException if the URI cannot be opened
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
     */
    public static AudioPlayer open(final URI uri, final AudioDevice audioDevice) throws IOException, UnsupportedAudioFileException {
        Exception lastException;

        // prefer AVFoundationPlayer on macOS, because it probably
        // uses the least system resources
        if (MAC) {
            try {
                final AudioPlayer audioPlayer = new AVFoundationPlayer();
                if (audioDevice != null) {
                    audioPlayer.setAudioDevice(audioDevice);
                }
                audioPlayer.open(uri);
                return audioPlayer;
            } catch (Exception e) {
                LOG.log(Level.INFO, "Failed to open with " + AVFoundationPlayer.class.getSimpleName() + ": " + uri, e);
            }
        }

        // Java is always available, but can it play the desired resource?
        try {
            final AudioPlayer audioPlayer = new JavaPlayer();
            if (audioDevice != null) {
                audioPlayer.setAudioDevice(audioDevice);
            }
            audioPlayer.open(uri);
            return audioPlayer;
        } catch (Exception e) {
            LOG.log(Level.INFO, "Failed to open with " + JavaPlayer.class.getSimpleName() + ": " + uri, e);
            lastException = e;
        }

        // last fallback, if JavaFX is available
        if (isJavaFXAvailable()) {
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
        } else throw (RuntimeException) lastException;
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
