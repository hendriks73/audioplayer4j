/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

/**
 * AudioDevice listener.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public interface AudioDevicesListener {

    /**
     * Is called, whenever {@link AudioDevice}s are added or removed.
     *
     * @param oldDevices previously available devices
     * @param currentDevices currently available devices
     */
    void deviceChanged(AudioDevice[] oldDevices, AudioDevice[] currentDevices);

}
