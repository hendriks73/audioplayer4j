/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import com.tagtraum.audioplayer4j.device.DefaultAudioDevice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TestAudioPlayerFactory.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class TestAudioPlayerFactory {

    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    @BeforeAll
    public static void removeOldNativeLibs() throws IOException {
        if (MAC) {
            final Path path = Paths.get(System.getProperty("java.io.tmpdir"));
            try (final Stream<Path> walk = Files.walk(path, 1)) {
                final List<Path> dylibs = walk.filter(p -> p.getFileName().toString().endsWith(".dylib")).collect(Collectors.toList());
                for (final Path p : dylibs)
                    Files.deleteIfExists(p);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("testOpenURI")
    public void testOpenURIWithAudioDevice(final URI uri) throws IOException {
        final AudioDevice audioDevice = DefaultAudioDevice.getInstance();
        try (final AudioPlayer player = AudioPlayerFactory.open(uri, audioDevice)) {
            assertEquals(0, player.getGain());
            assertEquals(1f, player.getVolume());
            assertTrue(player.isPaused());
            assertFalse(player.isMuted());
            assertEquals(uri, player.getURI());
            assertNotNull(player.getDuration());
            assertFalse(player.getDuration().isNegative());
            assertFalse(player.getDuration().isZero());
            assertEquals(ZERO, player.getTime());
        } catch (UnsupportedAudioFileException e) {
            System.out.println("Not supported: " + e);
        }
    }

    @ParameterizedTest
    @MethodSource("testOpenURI")
    public void testOpenURIWithUnsupportedAudioDevice(final URI uri) {
        final AudioDevice audioDevice = new AudioDevice() {
            @Override
            public String getName() {
                return "Unsupported";
            }

            @Override
            public boolean isDefault() {
                return false;
            }

            @Override
            public Mixer getMixer() {
                return null;
            }

            @Override
            public Line getLine(final Line.Info info) throws LineUnavailableException {
                throw new LineUnavailableException();
            }
        };
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            AudioPlayerFactory.open(uri, audioDevice);
        });
    }

    @ParameterizedTest
    @MethodSource
    public void testOpenURI(final URI uri) throws IOException {
        try (final AudioPlayer player = AudioPlayerFactory.open(uri)) {
            TestAudioPlayer.testOpenAudioPlayer(uri, player);

        } catch (UnsupportedAudioFileException e) {
            System.out.println("Not supported: " + e);
        }
    }

    public static Stream<URI> testOpenURI() {
        final List<String> resources = new ArrayList<>(Arrays.asList(
            "test.aiff",
            "test.flac",
            "test.wav",
            "test.mp3",
            "test.ogg",
            "test.wma"
        ));
        // m4a may not be supported by default on platforms other than macOS
        if (MAC) {
            resources.add("test.m4a");
        }
        return resources.stream().map(TestAudioPlayer::extractFile).map(Path::toUri);
    }

}
