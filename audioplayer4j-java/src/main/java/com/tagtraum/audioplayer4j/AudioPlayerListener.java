/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import java.net.URI;

/**
 * Listener for {@link AudioPlayer} start and finish events.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public interface AudioPlayerListener {

    /**
     * Playback of an audio resource has started.
     *
     * @param audioPlayer player that plays the resource
     * @param uri URI describing the audio resources
     */
    void started(AudioPlayer audioPlayer, URI uri);

    /**
     * Playback of an audio resource has ended.
     * Note that this does not necessarily mean that the resource
     * has been played until the end. The player instance
     * may simply have been closed.
     *
     * @param audioPlayer player that plays the resource
     * @param uri         URI describing the audio resources
     * @param endOfMedia  indicates whether the resource has been played until the end
     */
    void finished(AudioPlayer audioPlayer, URI uri, boolean endOfMedia);

}
