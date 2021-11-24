/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

/**
 * Runtime player exception.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class AudioPlayerException extends RuntimeException {

    /**
     * Runtime player exception with underlying cause.
     *
     * @param cause cause
     */
    public AudioPlayerException(final Throwable cause) {
        super(cause);
    }
}
