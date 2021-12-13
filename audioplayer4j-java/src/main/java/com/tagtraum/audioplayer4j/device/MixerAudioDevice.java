/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.device;

import com.tagtraum.audioplayer4j.AudioDevice;

import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic, {@link Mixer}-based AudioDevice.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class MixerAudioDevice implements AudioDevice {

    private static final Logger LOG = Logger.getLogger(MixerAudioDevice.class.getName());
    private final Mixer mixer;

    public MixerAudioDevice(final Mixer mixer) {
        this.mixer = mixer;
    }

    @Override
    public String getName() {
        return mixer.getMixerInfo().getName();
    }

    @Override
    public boolean isDefault() {
        return false;
    }

    @Override
    public Mixer getMixer() {
        return mixer;
    }

    @Override
    public Line getLine(final Line.Info info) throws LineUnavailableException {
        if (!mixer.isOpen()) {
            LOG.warning("Attempt to get a line from a closed mixer: " + mixer + " (" + getName() + ")");
        }
        if (!mixer.isLineSupported(info)) {
            LOG.warning("Attempt to get an unsupported line from a mixer: " + info + ", " + mixer + " (" + getName() + ")");
        }

        logLineStats(info);
        return mixer.getLine(info);
    }

    /**
     * Log info about how many lines are open and how many lines may be opened.
     *
     * @param info line info
     */
    private void logLineStats(final Line.Info info) {
        final int maxLines = mixer.getMaxLines(info);
        int matchingOpenLines = 0;
        for (final Line line : mixer.getSourceLines()) {
            if (line.isOpen() && line.getLineInfo().matches(info)) matchingOpenLines++;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Open lines for " + info + " on " + getName()
                + ": " + matchingOpenLines + "/" + maxLines);
        }
        if (maxLines > 0 && matchingOpenLines == maxLines) {
            LOG.warning("No more lines for " + info + " on " + getName());
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final AudioDevice that = (AudioDevice) o;

        if (mixer != null ? !mixer.equals(that.getMixer()) : that.getMixer() != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return 42;
    }

    @Override
    public String toString() {
        return "MixerAudioDevice{" +
            "mixer=" + mixer +
            ", name=" + getName() +
            ", open=" + mixer.isOpen() +
            '}';
    }
}
