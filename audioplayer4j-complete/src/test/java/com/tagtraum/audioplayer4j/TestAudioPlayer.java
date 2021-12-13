/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import com.tagtraum.audioplayer4j.device.MixerAudioDevice;
import com.tagtraum.audioplayer4j.java.JavaPlayer;
import com.tagtraum.audioplayer4j.javafx.JavaFXPlayer;
import com.tagtraum.audioplayer4j.macos.AVFoundationPlayer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sound.sampled.*;
import javax.swing.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * TestAudioPlayer.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class TestAudioPlayer {

    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");


    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testSwitchAudioDeviceWhilePlaying(final AudioPlayer audioPlayer) throws UnsupportedAudioFileException, IOException {
        final URI uri = extractFile("test.wav").toUri();
        audioPlayer.open(uri);
        audioPlayer.play();

        final MixerAudioDevice mixerDevice = new MixerAudioDevice(getMixer());
        try {
            audioPlayer.setAudioDevice(mixerDevice);
        } catch(IllegalArgumentException e) {
            if (audioPlayer instanceof JavaPlayer) {
                fail("JavaPlayer must support device " + mixerDevice);
            }
            System.out.println("Device " + mixerDevice + " is not supported by player " + audioPlayer.getClass().getSimpleName());
        }
        assertFalse(audioPlayer.isPaused());
        audioPlayer.close();
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testSwitchAudioDevice(final AudioPlayer audioPlayer) throws UnsupportedAudioFileException, IOException {
        final URI uri = extractFile("test.wav").toUri();
        audioPlayer.open(uri);

        final MixerAudioDevice mixerDevice = new MixerAudioDevice(getMixer());
        try {
            audioPlayer.setAudioDevice(mixerDevice);
        } catch(IllegalArgumentException e) {
            if (audioPlayer instanceof JavaPlayer) {
                fail("JavaPlayer must support device " + mixerDevice);
            }
            System.out.println("Device " + mixerDevice + " is not supported by player " + audioPlayer.getClass().getSimpleName());
        }
        assertTrue(audioPlayer.isPaused());
        audioPlayer.close();
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

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testOpenCloseOpenClose(final AudioPlayer audioPlayer) throws UnsupportedAudioFileException, IOException, InterruptedException, InvocationTargetException {
        final URI uri = extractFile("test.wav").toUri();
        final MemoryPropertyChangeListener pausedListener = new MemoryPropertyChangeListener();
        final MemoryPropertyChangeListener uriListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("paused", pausedListener);
        audioPlayer.addPropertyChangeListener("uri", uriListener);

        audioPlayer.open(uri);
        audioPlayer.play();
        Thread.sleep(500);
        audioPlayer.close();

        audioPlayer.open(uri);
        audioPlayer.play();
        Thread.sleep(500);
        audioPlayer.close();
        Thread.sleep(500);

        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final String name = audioPlayer.getClass().getSimpleName();
        final String pausedmessage = "Failure for " + name + ". Paused events=" + pausedListener.getEvents();

        final Iterator<PropertyChangeEvent> pausedEvents = pausedListener.getEvents().iterator();

        final PropertyChangeEvent playEvent0 = pausedEvents.next();
        assertEquals("paused", playEvent0.getPropertyName());
        assertTrue((Boolean)playEvent0.getOldValue(), pausedmessage);
        assertFalse((Boolean)playEvent0.getNewValue(), pausedmessage);

        final PropertyChangeEvent closeEvent0 = pausedEvents.next();
        assertEquals("paused", closeEvent0.getPropertyName());
        assertFalse((Boolean)closeEvent0.getOldValue(), pausedmessage);
        assertTrue((Boolean)closeEvent0.getNewValue(), pausedmessage);

        final PropertyChangeEvent playEvent1 = pausedEvents.next();
        assertEquals("paused", playEvent1.getPropertyName());
        assertTrue((Boolean)playEvent1.getOldValue(), pausedmessage);
        assertFalse((Boolean)playEvent1.getNewValue(), pausedmessage);

        final PropertyChangeEvent closeEvent1 = pausedEvents.next();
        assertEquals("paused", closeEvent1.getPropertyName());
        assertFalse((Boolean)closeEvent1.getOldValue(), pausedmessage);
        assertTrue((Boolean)closeEvent1.getNewValue(), pausedmessage);

        if (pausedEvents.hasNext())
            assertFalse(pausedEvents.hasNext(), "Unexpected event: " + pausedEvents.next() + ", all events: " + pausedListener.getEvents());


        final String uriMessage = "Failure for " + name + ". URI events=" + pausedListener.getEvents();

        final Iterator<PropertyChangeEvent> uriEvents = uriListener.getEvents().iterator();

        final PropertyChangeEvent openEvent0 = uriEvents.next();
        assertEquals("uri", openEvent0.getPropertyName());
        assertNull(openEvent0.getOldValue(), uriMessage);
        assertEquals(uri, openEvent0.getNewValue(), uriMessage);

        final PropertyChangeEvent closeEvent2 = uriEvents.next();
        assertEquals("uri", closeEvent2.getPropertyName());
        assertEquals(uri, closeEvent2.getOldValue(), uriMessage);
        assertNull(closeEvent2.getNewValue(), uriMessage);

        final PropertyChangeEvent openEvent1 = uriEvents.next();
        assertEquals("uri", openEvent1.getPropertyName());
        assertNull(openEvent1.getOldValue(), uriMessage);
        assertEquals(uri, openEvent1.getNewValue(), uriMessage);

        final PropertyChangeEvent closeEvent3 = uriEvents.next();
        assertEquals("uri", closeEvent3.getPropertyName());
        assertEquals(uri, closeEvent3.getOldValue(), uriMessage);
        assertNull(closeEvent3.getNewValue(), uriMessage);

        if (uriEvents.hasNext())
            assertFalse(uriEvents.hasNext(), "Unexpected event: " + uriEvents.next() + ", all events: " + uriListener.getEvents());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testOpenAudioPlayer(final AudioPlayer audioPlayer) throws UnsupportedAudioFileException, IOException {
        final Path file = extractFile("test.wav");
        audioPlayer.open(file.toUri());

        // sync with EDT
        try {
            SwingUtilities.invokeAndWait(() -> {});
        } catch (Exception e) {
            // ignore
        }
        testOpenAudioPlayer(audioPlayer.getURI(), audioPlayer);
    }

    @RepeatedTest(10)
    public void repeatedTestTimeForFile() throws UnsupportedAudioFileException, IOException, InterruptedException, InvocationTargetException {
        testTimeForFile(new JavaPlayer());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testTimeForFile(final AudioPlayer audioPlayer) throws IOException, InterruptedException, InvocationTargetException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener listener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("time", listener);

        final Path file = extractFile("test.wav");

        assertNull(audioPlayer.getTime());
        assertThrows(IllegalStateException.class, () -> audioPlayer.setTime(ofMillis(100)));

        audioPlayer.open(file.toUri());

        assertThrows(IllegalArgumentException.class, () -> audioPlayer.setTime(ofMillis(-100)));
        assertThrows(NullPointerException.class, () -> audioPlayer.setTime(null));

        assertEquals(ZERO, audioPlayer.getTime());
        audioPlayer.setTime(ofMillis(500));

        // give it a second, some players need some time for seeking
        Thread.sleep(500);

        assertEquals(ofMillis(500), audioPlayer.getTime());

        audioPlayer.setTime(ofMillis(200));

        // give it a second, some players need some time for seeking
        Thread.sleep(500);

        assertEquals(ofMillis(200), audioPlayer.getTime());

        // set time greater than duration
        final Duration duration = audioPlayer.getDuration();
        assertThrows(IllegalArgumentException.class, () -> audioPlayer.setTime(duration.plus(5, SECONDS)));

        // set time to duration
        audioPlayer.setTime(duration);

        // give it a second, some players need some time for seeking
        Thread.sleep(500);

        audioPlayer.close();

        assertThrows(IllegalStateException.class, () -> audioPlayer.setTime(ofMillis(100)));

        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final String name = audioPlayer.getClass().getSimpleName();
        final String message = "Failure for " + name + ". Time events=" + listener.getEvents();

        final Iterator<PropertyChangeEvent> events = listener.getEvents().iterator();

        final PropertyChangeEvent openEvent = events.next();
        assertEquals("time", openEvent.getPropertyName());
        assertNull(openEvent.getOldValue(), message);
        assertEquals(ZERO, openEvent.getNewValue(), message);

        final PropertyChangeEvent seekEvent = events.next();
        assertEquals("time", seekEvent.getPropertyName());
        assertEquals(ZERO, seekEvent.getOldValue(), message);
        assertEquals(ofMillis(500), seekEvent.getNewValue(), message);

        final PropertyChangeEvent seekEvent2 = events.next();
        assertEquals("time", seekEvent2.getPropertyName());
        assertEquals(ofMillis(500), seekEvent2.getOldValue(), message);
        assertEquals(ofMillis(200), seekEvent2.getNewValue(), message);

        final PropertyChangeEvent seekEvent3 = events.next();
        assertEquals("time", seekEvent3.getPropertyName());
        assertEquals(ofMillis(200), seekEvent3.getOldValue(), message);
        assertEquals(duration.toMillis(), ((Duration)seekEvent3.getNewValue()).toMillis(), message);

        final PropertyChangeEvent closeEvent = events.next();
        assertEquals("time", closeEvent.getPropertyName());
        assertEquals(duration.toMillis(), ((Duration)closeEvent.getOldValue()).toMillis(), message);
        assertNull(closeEvent.getNewValue(), message);

        if (events.hasNext())
            assertFalse(events.hasNext(), "Unexpected event: " + events.next() + ", all events: " + listener.getEvents());
    }

    @RepeatedTest(10)
    public void repeatedTestTimeForFileWhilePlaying() throws UnsupportedAudioFileException, IOException, InterruptedException {
        testTimeForFileWhilePlaying(new JavaPlayer());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testTimeForFileWhilePlaying(final AudioPlayer audioPlayer) throws IOException, InterruptedException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener listener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("time", listener);

        final CountDownLatch started = new CountDownLatch(1);
        audioPlayer.addAudioPlayerListener(new AudioPlayerListener() {
            @Override
            public void started(final AudioPlayer audioPlayer, final URI uri) {
                started.countDown();
            }

            @Override
            public void finished(final AudioPlayer audioPlayer, final URI uri) {

            }
        });

        final Path file = extractFile("test.wav");
        audioPlayer.open(file.toUri());
        assertEquals(ZERO, audioPlayer.getTime());
        audioPlayer.play();
        started.await();

        // give it a second, some players need some time to open the file/start playback
        Thread.sleep(500);

        audioPlayer.setTime(ofMillis(1000));

        // give it a second, some players need some time for seeking
        Thread.sleep(500);

        // should be between 1000 and 1700 now
        final String name = audioPlayer.getClass().getSimpleName();
        final long start0 = System.nanoTime();
        final Duration time0 = audioPlayer.getTime();
        final long timeCallDuration0 = (System.nanoTime() - start0) / 1000L;
        System.out.println("timeCallDuration0 = " + timeCallDuration0);
        assertNotNull(time0, "Player time must not be null, but apparently is.");
        assertTrue(time0.compareTo(ofMillis(1000 + timeCallDuration0/1000L)) >= 0, name + ": Time should be greater than/equal to 1.0s (+" + timeCallDuration0 + "micros), but isn't: " + time0);
        assertTrue(time0.compareTo(ofMillis(1700 + timeCallDuration0/1000L)) < 0, name + ": Time should be less than 1.7s (+" + timeCallDuration0 + "micros), but isn't: " + time0);

        audioPlayer.setTime(ofMillis(200));

        // give it a second, some players need some time for seeking
        Thread.sleep(500);

        // should be between 200 and 900 now
        final long start1 = System.nanoTime();
        final Duration time1 = audioPlayer.getTime();
        final long timeCallDuration1 = (System.nanoTime() - start1) / 1000L;
        System.out.println("timeCallDuration1 = " + timeCallDuration1);
        assertNotNull(time1, "Player time must not be null, but apparently is.");
        assertTrue(time1.compareTo(ofMillis(200 + timeCallDuration1/1000L)) >= 0, name + ": Time should be greater than/equal to 0.2s (+" + timeCallDuration1 + "micros), but isn't: " + time1);
        assertTrue(time1.compareTo(ofMillis(900 + timeCallDuration1/1000L)) < 0, name + ": Time should be less than 0.9s (+" + timeCallDuration1 + "micros), but isn't: " + time1);

        audioPlayer.close();
    }


    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testURI(final AudioPlayer audioPlayer) throws IOException, InterruptedException, InvocationTargetException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener listener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("uri", listener);

        final URI uri = extractFile("test.wav").toUri();
        audioPlayer.open(uri);
        assertEquals(uri, audioPlayer.getURI());
        audioPlayer.close();
        // ensure no more events, if player is already closed
        audioPlayer.close();
        assertNull(audioPlayer.getURI());

        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final Iterator<PropertyChangeEvent> events = listener.getEvents().iterator();

        final PropertyChangeEvent openEvent = events.next();
        assertEquals("uri", openEvent.getPropertyName());
        assertNull(openEvent.getOldValue());
        assertEquals(uri, openEvent.getNewValue());

        final PropertyChangeEvent closeEvent = events.next();
        assertEquals("uri", closeEvent.getPropertyName());
        assertEquals(uri, closeEvent.getOldValue());
        assertNull(closeEvent.getNewValue());

        if (events.hasNext())
            assertFalse(events.hasNext(), "Unexpected event: " + events.next() + ", all events: " + listener.getEvents());
    }


    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testDurationForFile(final AudioPlayer audioPlayer) throws IOException, InterruptedException, InvocationTargetException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener listener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("duration", listener);

        final URI uri = extractFile("test.wav").toUri();
        final AudioFileFormat audioFileFormat = AudioSystem.getAudioFileFormat(uri.toURL());
        final long durationNS = (long) (audioFileFormat.getFrameLength() / audioFileFormat
            .getFormat().getFrameRate() * 1000L * 1000L * 1000L);

        audioPlayer.open(uri);
        final Duration trackDuration = Duration.ofNanos(durationNS);
        final Duration reportedDuration = audioPlayer.getDuration();
        assertEquals(trackDuration.getSeconds(), reportedDuration.getSeconds());
        assertEquals(trackDuration.getNano() / 1000000, reportedDuration.getNano() / 1000000);
        audioPlayer.close();
        assertNull(audioPlayer.getDuration());

        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final Iterator<PropertyChangeEvent> events = listener.getEvents().iterator();

        final PropertyChangeEvent openEvent = events.next();
        assertEquals("duration", openEvent.getPropertyName());
        assertNull(openEvent.getOldValue());
        assertEquals(reportedDuration, openEvent.getNewValue());

        final PropertyChangeEvent closeEvent = events.next();
        assertEquals("duration", closeEvent.getPropertyName());
        assertEquals(reportedDuration, closeEvent.getOldValue());
        assertNull(closeEvent.getNewValue());

        if (events.hasNext())
            assertFalse(events.hasNext(), "Unexpected event: " + events.next() + ", all events: " + listener.getEvents());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testVolumeAndMute(final AudioPlayer audioPlayer) throws IOException, InterruptedException, InvocationTargetException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener volumeListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("volume", volumeListener);

        final MemoryPropertyChangeListener mutedListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("muted", mutedListener);

        Assertions.assertThrows(IllegalArgumentException.class, () -> {audioPlayer.setVolume(-1f);});
        Assertions.assertThrows(IllegalArgumentException.class, () -> {audioPlayer.setVolume(1.0001f);});

        final URI uri = extractFile("test.wav").toUri();
        assertEquals(1f, audioPlayer.getVolume());

        audioPlayer.setVolume(0.75f);

        assertEquals(0.75f, audioPlayer.getVolume());
        audioPlayer.open(uri);
        assertEquals(0.75f, audioPlayer.getVolume());

        audioPlayer.setVolume(0.5f);

        assertEquals(0.5f, audioPlayer.getVolume());
        assertFalse(audioPlayer.isMuted());

        audioPlayer.setMuted(true);

        assertTrue(audioPlayer.isMuted());
        assertEquals(0.5f, audioPlayer.getVolume());

        audioPlayer.setMuted(false);

        // give it some time
        Thread.sleep(200);

        assertFalse(audioPlayer.isMuted());
        assertEquals(0.5f, audioPlayer.getVolume());

        // test auto-unmute (i.e. muted, then set volume to > 0)
        audioPlayer.setMuted(true);
        assertTrue(audioPlayer.isMuted());
        audioPlayer.setVolume(0.9f);

        audioPlayer.close();


        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final Iterator<PropertyChangeEvent> volumeEvents = volumeListener.getEvents().iterator();

        final PropertyChangeEvent e75 = volumeEvents.next();
        assertEquals("volume", e75.getPropertyName());
        assertEquals(1f, e75.getOldValue());
        assertEquals(0.75f, e75.getNewValue());

        final PropertyChangeEvent e5 = volumeEvents.next();
        assertEquals("volume", e5.getPropertyName());
        assertEquals(0.75f, e5.getOldValue());
        assertEquals(0.5f, e5.getNewValue());

        final PropertyChangeEvent e9 = volumeEvents.next();
        assertEquals("volume", e9.getPropertyName());
        assertEquals(0.5f, e9.getOldValue());
        assertEquals(0.9f, e9.getNewValue());

        assertFalse(volumeEvents.hasNext());

        final Iterator<PropertyChangeEvent> mutedEvents = mutedListener.getEvents().iterator();

        final PropertyChangeEvent eMuted0 = mutedEvents.next();
        assertEquals("muted", eMuted0.getPropertyName());
        assertEquals(false, eMuted0.getOldValue());
        assertEquals(true, eMuted0.getNewValue());

        final PropertyChangeEvent eUnmuted0 = mutedEvents.next();
        assertEquals("muted", eUnmuted0.getPropertyName());
        assertEquals(true, eUnmuted0.getOldValue());
        assertEquals(false, eUnmuted0.getNewValue());

        final PropertyChangeEvent eMuted1 = mutedEvents.next();
        assertEquals("muted", eMuted1.getPropertyName());
        assertEquals(false, eMuted1.getOldValue());
        assertEquals(true, eMuted1.getNewValue());

        final PropertyChangeEvent eUnmuted1 = mutedEvents.next();
        assertEquals("muted", eUnmuted1.getPropertyName());
        assertEquals(true, eUnmuted1.getOldValue());
        assertEquals(false, eUnmuted1.getNewValue());

        assertFalse(mutedEvents.hasNext());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testVolumeAndGain(final AudioPlayer audioPlayer) throws IOException, InterruptedException, InvocationTargetException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener volumeListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("volume", volumeListener);

        final MemoryPropertyChangeListener gainListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("gain", gainListener);

        final URI uri = extractFile("test.wav").toUri();
        assertEquals(1f, audioPlayer.getVolume());
        assertEquals(0f, audioPlayer.getGain());

        audioPlayer.setVolume(0.75f);

        assertEquals(0f, audioPlayer.getGain());
        assertEquals(0.75f, audioPlayer.getVolume());
        assertEquals(0.75f, audioPlayer.getEffectiveVolume());

        audioPlayer.setGain(-5);

        assertEquals(-5f, audioPlayer.getGain());
        assertEquals(0.75f, audioPlayer.getVolume());
        final float effectiveVolume = AudioPlayer.gainToVolume(AudioPlayer.volumeToGain(0.75f) - 5f);
        assertEquals(effectiveVolume, audioPlayer.getEffectiveVolume());

        audioPlayer.open(uri);
        assertEquals(0.75f, audioPlayer.getVolume());
        assertEquals(-5f, audioPlayer.getGain());
        assertEquals(effectiveVolume, audioPlayer.getEffectiveVolume());

        audioPlayer.setVolume(0.5f);

        assertEquals(0.5f, audioPlayer.getVolume(), 0.00001f);
        assertEquals(-5f, audioPlayer.getGain());

        audioPlayer.setGain(-7);

        assertEquals(-7f, audioPlayer.getGain());
        assertEquals(0.5f, audioPlayer.getVolume());

        audioPlayer.setGain(0f);

        assertEquals(0f, audioPlayer.getGain());
        assertEquals(0.5f, audioPlayer.getVolume());

        audioPlayer.close();


        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final Iterator<PropertyChangeEvent> volumeEvents = volumeListener.getEvents().iterator();

        final PropertyChangeEvent e75 = volumeEvents.next();
        assertEquals("volume", e75.getPropertyName());
        assertEquals(1f, e75.getOldValue());
        assertEquals(0.75f, e75.getNewValue());

        final PropertyChangeEvent e5 = volumeEvents.next();
        assertEquals("volume", e5.getPropertyName());
        assertEquals(0.75f, e5.getOldValue());
        assertEquals(0.5f, e5.getNewValue());

        assertFalse(volumeEvents.hasNext());

        final Iterator<PropertyChangeEvent> gainEvents = gainListener.getEvents().iterator();

        final PropertyChangeEvent g5 = gainEvents.next();
        assertEquals("gain", g5.getPropertyName());
        assertEquals(0f, g5.getOldValue());
        assertEquals(-5f, g5.getNewValue());

        final PropertyChangeEvent g7 = gainEvents.next();
        assertEquals("gain", g7.getPropertyName());
        assertEquals(-5f, g7.getOldValue());
        assertEquals(-7f, g7.getNewValue());

        final PropertyChangeEvent g0 = gainEvents.next();
        assertEquals("gain", g0.getPropertyName());
        assertEquals(-7f, g0.getOldValue());
        assertEquals(0f, g0.getNewValue());

        assertFalse(gainEvents.hasNext());
    }


    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testPause(final AudioPlayer audioPlayer) throws IOException, InterruptedException, InvocationTargetException, UnsupportedAudioFileException {

        final MemoryPropertyChangeListener pausedListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("paused", pausedListener);

        final URI uri = extractFile("test.wav").toUri();
        assertTrue(audioPlayer.isPaused());

        audioPlayer.open(uri);

        assertTrue(audioPlayer.isPaused());

        audioPlayer.play();

        // playback may not be instantaneous
        Thread.sleep(200);
        assertFalse(audioPlayer.isPaused());


        audioPlayer.pause();
        audioPlayer.pause();

        // pausing may not be instantaneous
        Thread.sleep(200);
        assertTrue(audioPlayer.isPaused());

        audioPlayer.play();

        // playback may not be instantaneous
        Thread.sleep(200);
        assertFalse(audioPlayer.isPaused());

        // closing may not be instantaneous
        audioPlayer.close();

        assertTrue(audioPlayer.isPaused());


        // sync with EDT
        SwingUtilities.invokeAndWait(() -> {});

        final Iterator<PropertyChangeEvent> pausedEvents = pausedListener.getEvents().iterator();

        final PropertyChangeEvent e0 = pausedEvents.next();
        assertEquals("paused", e0.getPropertyName());
        assertTrue((Boolean) e0.getOldValue());
        assertFalse((Boolean) e0.getNewValue());

        final PropertyChangeEvent e1 = pausedEvents.next();
        assertEquals("paused", e1.getPropertyName());
        assertFalse((Boolean) e1.getOldValue());
        assertTrue((Boolean) e1.getNewValue());

        final PropertyChangeEvent e2 = pausedEvents.next();
        assertEquals("paused", e2.getPropertyName());
        assertTrue((Boolean) e2.getOldValue());
        assertFalse((Boolean) e2.getNewValue());

        final PropertyChangeEvent e3 = pausedEvents.next();
        assertEquals("paused", e3.getPropertyName());
        assertFalse((Boolean) e3.getOldValue());
        assertTrue((Boolean) e3.getNewValue());

        assertFalse(pausedEvents.hasNext());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testStarted(final AudioPlayer audioPlayer) throws IOException, InterruptedException, UnsupportedAudioFileException {
        final MemoryAudioPlayerListener listener = new MemoryAudioPlayerListener();
        audioPlayer.addAudioPlayerListener(listener);

        final URI uri = extractFile("test.wav").toUri();
        audioPlayer.open(uri);
        audioPlayer.play();
        Thread.sleep(2000);
        audioPlayer.play();
        audioPlayer.playPause();
        audioPlayer.play();
        audioPlayer.pause();

        Thread.sleep(200);
        audioPlayer.close();

        Thread.sleep(2000);

        final Iterator<String> iterator = listener.getEvents().iterator();

        assertEquals("started", iterator.next());
        assertEquals("finished", iterator.next());

        assertFalse(iterator.hasNext());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testFinished(final AudioPlayer audioPlayer) throws IOException, InterruptedException, UnsupportedAudioFileException {
        final MemoryAudioPlayerListener listener = new MemoryAudioPlayerListener();
        audioPlayer.addAudioPlayerListener(listener);
        final MemoryPropertyChangeListener timeListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("time", timeListener);

        final URI uri = extractFile("test.wav").toUri();
        audioPlayer.open(uri);
        audioPlayer.play();
        audioPlayer.pause();
        audioPlayer.setTime(audioPlayer.getDuration().minus(Duration.of(500, MILLIS)));
        audioPlayer.play();

        Thread.sleep(3000);

        assertEquals(2, listener.getEvents().size(),
            "Before close(). Expected 2 events for " + audioPlayer.getClass().getSimpleName() + " and got: " + listener.getEvents() + ", time events: " + timeListener.getEvents());
        final Iterator<String> iterator = listener.getEvents().iterator();

        assertEquals("started", iterator.next());
        assertEquals("finished", iterator.next());

        assertFalse(iterator.hasNext());

        audioPlayer.close();

        Thread.sleep(200);

        assertEquals(2, listener.getEvents().size(),
            "After close(). Expected 2 events for " + audioPlayer.getClass().getSimpleName() + " and got: " + listener.getEvents());
    }

    /**
     * Standard tests for an audio player that has opened a URI.
     *
     * @param uri    uri
     * @param player player
     */
    public static void testOpenAudioPlayer(final URI uri, final AudioPlayer player) {

        // basics
        assertEquals(uri, player.getURI());
        assertTrue(player.isPaused());

        // gain/volume/muted
        assertEquals(1f, player.getVolume());
        assertEquals(0, player.getGain());

        // change gain
        player.setGain(-5);
        assertEquals(-5, player.getGain());
        assertEquals(1f, player.getVolume());

        // change gain back
        player.setGain(0);
        assertEquals(1f, player.getVolume());
        assertEquals(0, player.getGain());

        // set mute
        assertFalse(player.isMuted());
        player.setMuted(true);
        assertTrue(player.isMuted());
        assertEquals(1f, player.getVolume());
        assertEquals(0, player.getGain());
        player.setMuted(false);

        // duration
        assertNotNull(player.getDuration());
        assertFalse(player.getDuration().isNegative());
        assertFalse(player.getDuration().isZero());
    }

    @Test
    public void testGainToVolume() {
        assertEquals(0.0001f, AudioPlayer.gainToVolume(-80f));
        assertEquals(1f, AudioPlayer.gainToVolume(80f));
        final float volumeP200 = AudioPlayer.gainToVolume(200);
        assertTrue(volumeP200>=0f && volumeP200<=1f);
        final float volumeM200 = AudioPlayer.gainToVolume(-200);
        assertTrue(volumeM200>=0f && volumeM200<=1f);
        final float volumeM5 = AudioPlayer.gainToVolume(-5);
        assertTrue(volumeM5>=0f && volumeM5<=1f);
        final float volumeP5 = AudioPlayer.gainToVolume(5);
        assertTrue(volumeP5>=0f && volumeP5<=1f);
    }

    @Test
    public void testVolumeToGain() {
        assertEquals(0f, AudioPlayer.volumeToGain(1f));
        assertEquals(-20f, AudioPlayer.volumeToGain(0.1f));
        assertEquals(-80f, AudioPlayer.volumeToGain(0.0001f));
        assertEquals(-80f, AudioPlayer.volumeToGain(0f));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testMinTimeEventDifference(final AudioPlayer player) {
        assertEquals(200, player.getMinTimeEventDifference());
        player.setMinTimeEventDifference(400);
        assertEquals(400, player.getMinTimeEventDifference());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("players")
    public void testReset(final AudioPlayer audioPlayer) throws InterruptedException, UnsupportedAudioFileException, IOException {
        final MemoryAudioPlayerListener listener = new MemoryAudioPlayerListener();
        audioPlayer.addAudioPlayerListener(listener);
        final MemoryPropertyChangeListener timeListener = new MemoryPropertyChangeListener();
        audioPlayer.addPropertyChangeListener("time", timeListener);

        Assertions.assertThrows(IllegalStateException.class, audioPlayer::reset);

        final URI uri = extractFile("test.wav").toUri();
        audioPlayer.open(uri);
        audioPlayer.play();

        Thread.sleep(500);

        audioPlayer.reset();

        Thread.sleep(500);

        assertFalse(audioPlayer.isPaused());
        final Duration time = audioPlayer.getTime();
        assertTrue(time.compareTo(ofMillis(250)) >= 0);
        assertTrue(time.compareTo(ofMillis(750)) < 0);

        audioPlayer.close();

        Thread.sleep(500);

        assertEquals(2, listener.getEvents().size(),
            "Before close(). Expected 2 events for " + audioPlayer.getClass().getSimpleName() + " and got: " + listener.getEvents() + ", time events: " + timeListener.getEvents());
        final Iterator<String> iterator = listener.getEvents().iterator();

        assertEquals("started", iterator.next());
        assertEquals("finished", iterator.next());

        assertFalse(iterator.hasNext());
    }


    public static Stream<Arguments> players() {
        final List<Arguments> players = new ArrayList<>();

        // always available
        players.add(arguments(named("JavaPlayer", new JavaPlayer())));
        // only with JavaFX
        if (isJavaFXAvailable()) players.add(arguments(named("JavaFXPlayer", new JavaFXPlayer())));
        // only on macOS
        if (MAC) players.add(arguments(named("AVFoundationPlayer", new AVFoundationPlayer())));

        return players.stream();
    }

    public static Path extractFile(final String resource) {
        try {
            final Path file = Files.createTempFile("test", resource);
            try (final InputStream in = AudioPlayerFactory.class.getResourceAsStream(resource)) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            // not pretty, but convenient
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Test, if the JavaFX MediaPlayer is available.
     *
     * @return true or false
     */
    private static boolean isJavaFXAvailable() {
        boolean available = false;
        try {
            Class.forName("javafx.scene.media.MediaPlayer");
            available = true;
        } catch (Throwable e) {
            // not available
        }
        return available;
    }

    private static class MemoryPropertyChangeListener implements PropertyChangeListener {
        private final List<PropertyChangeEvent> events = new ArrayList<>();

        @Override
        public void propertyChange(final PropertyChangeEvent evt) {
            events.add(evt);
        }

        public List<PropertyChangeEvent> getEvents() {
            return events;
        }
    }

    private static class MemoryAudioPlayerListener implements AudioPlayerListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void started(final AudioPlayer audioPlayer, final URI uri) {
            events.add("started");
        }

        @Override
        public void finished(final AudioPlayer audioPlayer, final URI uri) {
            events.add("finished");
        }

        public List<String> getEvents() {
            return events;
        }
    }
}
