/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.device;

import com.tagtraum.audioplayer4j.AudioDevice;

import javax.sound.sampled.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default AudioDevice.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class DefaultAudioDevice implements AudioDevice {

    private static final Logger LOG = Logger.getLogger(DefaultAudioDevice.class.getName());
    private static final AudioDevice INSTANCE = new DefaultAudioDevice();

    private DefaultAudioDevice() {
    }

    public static AudioDevice getInstance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return "Default";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public Mixer getMixer() {
        final Mixer.Info[] mixerInfo = AudioSystem.getMixerInfo();
        if (mixerInfo != null && mixerInfo.length > 0) {
            try {
                for (final Mixer.Info mi : mixerInfo) {
                    final Mixer mixer = AudioSystem.getMixer(mi);
                    if (offersSourceDataLines(mixer)) return mixer;
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to get mixer for default device.", e);
            }
        }
        return null;
    }

    private static boolean offersSourceDataLines(final Mixer mixer) {
        final Line.Info[] sourceLineInfo = mixer.getSourceLineInfo(new Line.Info(SourceDataLine.class));
        return (sourceLineInfo != null && sourceLineInfo.length > 0);
    }

    @Override
    public Line getLine(final Line.Info info) throws LineUnavailableException {
        return AudioSystem.getLine(info);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final AudioDevice that = (AudioDevice) o;
        if (this.getMixer() != null ? !this.getMixer().equals(that.getMixer()) : that.getMixer() != null) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return 42;
    }

    @Override
    public String toString() {
        final Mixer mixer = getMixer();
        final String m = mixer == null ? "none" : mixer.toString();
        final String n = mixer == null ? "none" : mixer.getMixerInfo().getName();
        final String o = mixer == null ? "false" : ((Boolean)mixer.isOpen()).toString();
        return "DefaultAudioDevice{" +
            "mixer=" + m +
            ", name=" + n +
            ", open=" + o +
            '}';
    }
}
