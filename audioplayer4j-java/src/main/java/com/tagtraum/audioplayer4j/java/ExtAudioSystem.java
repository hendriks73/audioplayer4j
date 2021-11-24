/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.java;

import javax.sound.sampled.*;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wrapper around some {@link javax.sound.sampled.AudioSystem} methods in order to
 * influence the order in which SPIs are used.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public final class ExtAudioSystem {

    private static final Logger LOG = Logger.getLogger(ExtAudioSystem.class.getName());
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean MAC = OS_NAME.contains("mac");
    private static final boolean WINDOWS = OS_NAME.toLowerCase().contains("win");
    private static final boolean UNIX = OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix");
    private static final FormatConversionProvider FF_CONVERSION = createFFFormatConversionProvider();
    private static final FormatConversionProvider CA_CONVERSION = createCAFormatConversionProvider();

    private ExtAudioSystem() {
    }


    /**
     * Obtains the audio file format of the specified URL.  The URL must
     * point to valid audio file data.
     *
     * @param url the URL from which file format information should be
     * extracted
     * @return an <code>AudioFileFormat</code> object describing the audio file format
     * @throws UnsupportedAudioFileException if the URL does not point to valid audio
     * file data recognized by the system
     * @throws IOException if an input/output exception occurs
     */
    public static AudioFileFormat getAudioFileFormat(final URL url)
        throws UnsupportedAudioFileException, IOException {
        // prefer CoreAudio, if on mac
        if (MAC) {
            final AudioFileReader caAudioFileReader = createCAAudioFileReader();
            if (caAudioFileReader != null) {
                try {
                    return caAudioFileReader.getAudioFileFormat(url);
                } catch (UnsupportedAudioFileException | IOException e) {
                    // ignore
                }
            }
        }
        if (isAIFF(url.toString())) {
            final AudioFileReader audioFileReader = createAIFFAudioFileReader();
            if (audioFileReader != null) {
                try {
                    return audioFileReader.getAudioFileFormat(url);
                } catch (UnsupportedAudioFileException | IOException e) {
                    // ignore
                }
            }
        }
        // prefer FFMpeg, if on windows or *nix
        if (WINDOWS || UNIX) {
            final AudioFileReader ffAudioFileReader = createFFAudioFileReader();
            if (ffAudioFileReader != null) {
                try {
                    return ffAudioFileReader.getAudioFileFormat(url);
                } catch (UnsupportedAudioFileException | IOException e) {
                    // ignore
                }
            }
        }
        return AudioSystem.getAudioFileFormat(url);
    }


    /**
     * Obtains an audio input stream from the URL provided.  The URL must
     * point to valid audio file data.
     *
     * @param url the URL for which the <code>AudioInputStream</code> should be
     * constructed
     * @param bufferSize buffer size, may be ignored. Use small buffers for low latency.
     * @return an <code>AudioInputStream</code> object based on the audio file data pointed
     * to by the URL
     * @throws UnsupportedAudioFileException if the URL does not point to valid audio
     * file data recognized by the system
     * @throws IOException if an I/O exception occurs
     */
    public static AudioInputStream getAudioInputStream(final URL url, final int bufferSize)
        throws UnsupportedAudioFileException, IOException {
        if (LOG.isLoggable(Level.FINE)) LOG.fine("Opening AudioInputStream for " + url);
        // prefer CoreAudio, if on mac
        if (MAC) {
            final AudioFileReader caAudioFileReader = createCAAudioFileReader();
            if (caAudioFileReader != null) {
                try {
                    return (AudioInputStream)caAudioFileReader
                        .getClass()
                        .getMethod("getAudioInputStream", URL.class, Integer.TYPE)
                        .invoke(caAudioFileReader, url,  bufferSize);
                } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    LOG.log(Level.SEVERE, e.toString(), e);
                }
                try {
                    return caAudioFileReader.getAudioInputStream(url);
                } catch (UnsupportedAudioFileException | IOException e) {
                    // ignore
                }
            }
        }
        if (isAIFF(url.toString())) {
            final AudioFileReader audioFileReader = createAIFFAudioFileReader();
            if (audioFileReader != null) {
                try {
                    return audioFileReader.getAudioInputStream(url);
                } catch (UnsupportedAudioFileException | IOException e) {
                    // ignore
                }
            }
        }
        // prefer FFMpeg, if on windows or unix
        if (WINDOWS || UNIX) {
            final AudioFileReader ffAudioFileReader = createFFAudioFileReader();
            if (ffAudioFileReader != null) {
                try {
                    return ffAudioFileReader.getAudioInputStream(url);
                } catch (UnsupportedAudioFileException | IOException e) {
                    // ignore
                }
            }
        }
        return AudioSystem.getAudioInputStream(url);
    }

    /**
     * Obtains an audio input stream of the indicated format, by converting the
     * provided audio input stream.
     *
     * @param targetFormat the desired audio format after conversion
     * @param sourceStream the stream to be converted
     * @return an audio input stream of the indicated format
     * @throws IllegalArgumentException if the conversion is not supported
     * @see AudioSystem#getAudioInputStream(AudioFormat, AudioInputStream)
     */
    public static AudioInputStream getAudioInputStream(final AudioFormat targetFormat, AudioInputStream sourceStream) {
        // try to stick to one XXSampledSP package for optimal performance
        if (FF_CONVERSION.isConversionSupported(sourceStream.getFormat(), targetFormat)) {
            sourceStream = FF_CONVERSION.getAudioInputStream(targetFormat, sourceStream);
        } else if (CA_CONVERSION != null && CA_CONVERSION.isConversionSupported(sourceStream.getFormat(), targetFormat)) {
            sourceStream = CA_CONVERSION.getAudioInputStream(targetFormat, sourceStream);
        } else {
            sourceStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
        }
        return sourceStream;
    }

    private static AudioFileReader createAIFFAudioFileReader() {
        if (MAC) {
            try {
                return (AudioFileReader) Class.forName("com.tagtraum.casampledsp.CAAudioFileReader").getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
                LOG.log(Level.WARNING, "Failed to use com.tagtraum.casampledsp.CAAudioFileReader", e);
            }
        }
        try {
            return (AudioFileReader) Class.forName("com.sun.media.sound.AiffFileReader").getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
            LOG.log(Level.WARNING, "Failed to use com.sun.media.sound.AiffFileReader", e);
        }
        return null;
    }

    private static boolean isAIFF(final String path) {
        final String lowerCase = path.toLowerCase();
        return lowerCase.endsWith(".aif") || lowerCase.endsWith(".aiff");
    }


    private static FormatConversionProvider createCAFormatConversionProvider() {
        FormatConversionProvider conversion = null;
        try {
            conversion = (FormatConversionProvider)Class.forName("com.tagtraum.casampledsp.CAFormatConversionProvider").getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
            LOG.info("No CASampledSP installed.");
        }
        return conversion;
    }

    private static FormatConversionProvider createFFFormatConversionProvider() {
        FormatConversionProvider conversion = null;
        try {
            conversion = (FormatConversionProvider)Class.forName("com.tagtraum.ffsampledsp.FFFormatConversionProvider").getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
            LOG.info("No FFSampledSP installed.");
        }
        return conversion;
    }

    private static AudioFileReader createCAAudioFileReader() {
        AudioFileReader reader = null;
        try {
            reader = (AudioFileReader)Class.forName("com.tagtraum.casampledsp.CAAudioFileReader").getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
            LOG.info("No CASampledSP installed.");
        }
        return reader;
    }

    private static AudioFileReader createFFAudioFileReader() {
        AudioFileReader reader = null;
        try {
            reader = (AudioFileReader)Class.forName("com.tagtraum.ffsampledsp.FFAudioFileReader").getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
            LOG.info("No FFSampledSP installed.");
        }
        return reader;
    }

}
