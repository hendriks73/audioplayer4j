/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TestAudioPlayerException.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class TestAudioPlayerException {

    @Test
    public void testConstructor() {
        final RuntimeException cause = new RuntimeException();
        final AudioPlayerException exception = new AudioPlayerException(cause);
        assertEquals(cause, exception.getCause());
    }
}
