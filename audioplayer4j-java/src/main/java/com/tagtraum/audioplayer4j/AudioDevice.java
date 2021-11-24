/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;

/**
 * Represents an installed audio device.
 * See {@link AudioDevices#getAudioDevices()} for a list of available devices.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public interface AudioDevice {

    /**
     * Device name.
     *
     * @return name
     */
    String getName();

    /**
     * Is this the system's default device?
     *
     * @return true or false
     */
    boolean isDefault();

    /**
     * Obtain a {@link Mixer} from this device.
     *
     * @return mixer
     */
    Mixer getMixer();

    /**
     * Get a line from this device using the given {@link Line.Info}.
     *
     * @param info info, describing what's desired
     * @return line
     * @throws LineUnavailableException if no line conforming to the Info
     *  instance is available
     */
    Line getLine(Line.Info info) throws LineUnavailableException;
}
