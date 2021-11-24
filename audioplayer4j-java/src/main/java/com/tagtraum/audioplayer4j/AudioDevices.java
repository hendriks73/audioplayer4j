/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j;

import com.tagtraum.audioplayer4j.device.DefaultAudioDevice;
import com.tagtraum.audioplayer4j.device.MixerAudioDevice;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.swing.Timer;
import javax.swing.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Describes the audio devices installed on your system.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class AudioDevices {

    private static final Logger LOG = Logger.getLogger(AudioDevices.class.getName());
    private static final Timer timer;
    private static AudioDevice[] audioDevices = new AudioDevice[0];
    private static final List<AudioDevicesListener> listeners = new ArrayList<>();

    static {
        timer = new Timer(10000, e -> new Thread(AudioDevices::getAudioDevices).start());
        timer.setRepeats(true);
    }

    private AudioDevices() {
    }

    /**
     * List audio devices installed on the system.
     * It is possible that a device is listed twice - once as default
     * device and once as regular device.
     *
     * @return array of audio devices
     */
    public static AudioDevice[] getAudioDevices() {
        final List<AudioDevice> devices = new ArrayList<>();
        final Set<Mixer> nonDefaultMixers = new HashSet<>();
        final AudioDevice defaultAudioDevice = DefaultAudioDevice.getInstance();
        final Mixer defaultMixer = defaultAudioDevice.getMixer();
        if (defaultMixer != null) {
            devices.add(defaultAudioDevice);
            nonDefaultMixers.add(defaultMixer);
        }
        final Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (final Mixer.Info info : mixerInfos) {
            try {
                final Mixer mixer = AudioSystem.getMixer(info);
                if (!nonDefaultMixers.contains(mixer) && offersSourceDataLines(mixer)) {
                    devices.add(new MixerAudioDevice(mixer));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to get mixer for mixer info " + info, e);
            }
        }
        final AudioDevice[] oldAudioDevices = AudioDevices.audioDevices;
        final AudioDevice[] newDevices = devices.toArray(new AudioDevice[devices.size()]);
        AudioDevices.audioDevices = newDevices;
        if (!Arrays.equals(oldAudioDevices, newDevices)) {
            fireDeviceChanged(oldAudioDevices, newDevices);
        }
        return AudioDevices.audioDevices;
    }

    private static boolean offersSourceDataLines(final Mixer mixer) {
        final Line.Info[] sourceLineInfo = mixer.getSourceLineInfo(new Line.Info(SourceDataLine.class));
        return (sourceLineInfo != null && sourceLineInfo.length > 0);
    }

    /**
     * Add a listener.
     * Note that notifications aren't instantaneous and happen on the EDT.
     *
     * @param listener listener
     */
    public static void addAudioDevicesListener(final AudioDevicesListener listener) {
        listeners.add(listener);
        if (listeners.size() == 1) {
            startAudioDeviceObserver();
        }
    }

    /**
     * Removed a listener
     *
     * @param listener listener
     */
    public static void removeAudioDevicesListener(final AudioDevicesListener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            stopAudioDeviceObserver();
        }
    }

    private static void fireDeviceChanged(final AudioDevice[] oldDevices, final AudioDevice[] currentDevices) {
        final Runnable r = () -> {
            for (final AudioDevicesListener listener : listeners) {
                listener.deviceChanged(oldDevices, currentDevices);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private static void startAudioDeviceObserver() {
        timer.start();
        // initialize
        getAudioDevices();
    }

    private static void stopAudioDeviceObserver() {
        timer.stop();
    }

    /**
     * Indicates whether the class is periodically watching for changes.
     *
     * @return true, if the system is watching for changes
     */
    public static boolean isWatching() {
        return timer.isRunning();
    }
}
