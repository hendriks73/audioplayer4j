/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.device;

import com.tagtraum.audioplayer4j.AudioDevice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestMixerAudioDevice.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class TestMixerAudioDevice {

    @Test
    public void testBasics() {
        final Mixer mixer = getMixer();
        final AudioDevice device = new MixerAudioDevice(mixer);
        assertEquals(mixer, device.getMixer());
        assertEquals(mixer.getMixerInfo().getName(), device.getName());
        assertFalse(device.isDefault());
        Assertions.assertThrows(NullPointerException.class, () -> device.getLine(null));
        assertFalse(device.equals(null));
        assertFalse(device.equals(""));
        assertTrue(device.equals(device));
        assertFalse(device.equals(DefaultAudioDevice.getInstance()));
        assertNotNull(device.toString());
        assertEquals(42, device.hashCode());
    }

    private Mixer getMixer() {
        final Mixer.Info[] mixerInfo = AudioSystem.getMixerInfo();
        if (mixerInfo != null && mixerInfo.length > 0) {
            for (final Mixer.Info mi : mixerInfo) {
                final Mixer mixer = AudioSystem.getMixer(mi);
                if (offersSourceDataLines(mixer)) return mixer;
            }
        }
        return null;
    }

    private static boolean offersSourceDataLines(final Mixer mixer) {
        final Line.Info[] sourceLineInfo = mixer.getSourceLineInfo(new Line.Info(SourceDataLine.class));
        return (sourceLineInfo != null && sourceLineInfo.length > 0);
    }

    @Test
    public void testOpenStandardLine() throws LineUnavailableException {
        final Mixer mixer = getMixer();
        final AudioDevice device = new MixerAudioDevice(mixer);
        final AudioFormat cdFormat = new AudioFormat(44100f, 16, 2, true, true);
        final DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, cdFormat);
        final SourceDataLine line = (SourceDataLine)device.getLine(lineInfo);
        assertNotNull(line);
        assertEquals(cdFormat, line.getFormat());
        line.close();
    }

    @Test
    public void testOpenNonStandardLine() {
        final Mixer mixer = getMixer();
        final AudioDevice device = new MixerAudioDevice(mixer);
        final AudioFormat weirdFormat = new AudioFormat(44101f, 17, 5, true, true);
        final DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, weirdFormat);
        Assertions.assertThrows(IllegalArgumentException.class, () -> device.getLine(lineInfo));
    }
}
