/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TestAudioDevices.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class TestAudioDevices {

    @Test
    public void testGetDevices() {
        final AudioDevice[] audioDevices = AudioDevices.getAudioDevices();
    }

    @Test
    public void testListener() {
        final AudioDevicesListener audioDevicesListener0 = (oldDevices, currentDevices) -> {};
        final AudioDevicesListener audioDevicesListener1 = (oldDevices, currentDevices) -> {};
        assertFalse(AudioDevices.isWatching());
        AudioDevices.addAudioDevicesListener(audioDevicesListener0);
        assertTrue(AudioDevices.isWatching());
        AudioDevices.addAudioDevicesListener(audioDevicesListener1);
        AudioDevices.removeAudioDevicesListener(audioDevicesListener0);
        assertTrue(AudioDevices.isWatching());
        AudioDevices.removeAudioDevicesListener(audioDevicesListener1);
        assertFalse(AudioDevices.isWatching());
    }

}
