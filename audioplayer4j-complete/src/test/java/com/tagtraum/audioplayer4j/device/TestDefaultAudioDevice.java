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

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestDefaultAudioDevice.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class TestDefaultAudioDevice {

    @Test
    public void testBasics() {
        final AudioDevice device = DefaultAudioDevice.getInstance();
        assertEquals("Default", device.getName());
        assertTrue(device.isDefault());
        assertNotNull(device.getMixer());
        Assertions.assertThrows(NullPointerException.class, () -> device.getLine(null));
        assertFalse(device.equals(null));
        assertFalse(device.equals(""));
        assertTrue(device.equals(device));
        assertFalse(device.equals(new MixerAudioDevice(null)));
        assertNotNull(device.toString());
        assertEquals(42, device.hashCode());
    }
}
