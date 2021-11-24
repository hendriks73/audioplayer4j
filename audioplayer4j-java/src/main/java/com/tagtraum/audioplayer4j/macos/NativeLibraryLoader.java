/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.macos;

import com.tagtraum.audioplayer4j.AudioPlayer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Loader for the native libraries.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public final class NativeLibraryLoader {

    private static final Logger LOG = Logger.getLogger(NativeLibraryLoader.class.getName());
    public static final String VERSION = readProjectVersion();
    private static final String NATIVE_LIBRARY_EXTENSION = ".dylib";
    private static final Set<String> LOADED = new HashSet<>();
    private static final String OS_ARCH = System.getProperty("os.arch");
    private static Boolean libraryLoaded;

    private NativeLibraryLoader() {
    }

    /**
     * Loads the native library.
     *
     * @return true, if loading was successful
     */
    public static synchronized boolean loadLibrary() {
        if (libraryLoaded != null) {
            return libraryLoaded;
        }
        if ("aarch64".equals(OS_ARCH) || "arm64".equals(OS_ARCH)) {
            NativeLibraryLoader.loadLibrary("audioplayer4j-aarch64-macos");
        } else {
            NativeLibraryLoader.loadLibrary("audioplayer4j-x86_64-macos");
        }
        libraryLoaded = true;
        return libraryLoaded;
    }

    /**
     * Loads a library.
     *
     * @param libName name of the library, as described in {@link System#loadLibrary(String)} );
     */
    public static synchronized void loadLibrary(final String libName) {
        loadLibrary(libName, NativeLibraryLoader.class);
    }

    /**
     * Loads a library.
     *
     * @param libName name of the library, as described in {@link System#loadLibrary(String)} );
     * @param baseClass class that identifies the jar
     */
    public static synchronized void loadLibrary(final String libName, final Class<?> baseClass) {
        final String key = libName + "|" + baseClass.getName();
        if (LOADED.contains(key)) return;
        final String packagedNativeLib = libName + "-" + "0.9.0-SNAPSHOT" + NATIVE_LIBRARY_EXTENSION;
        //final String packagedNativeLib = libName + "-" + VERSION + NATIVE_LIBRARY_EXTENSION;
        final Path extractedNativeLib = Paths.get(System.getProperty("java.io.tmpdir") + "/" + packagedNativeLib);
        if (Files.notExists(extractedNativeLib)) {
            extractResourceToFile(baseClass, "/" + packagedNativeLib, extractedNativeLib);
        }
        Runtime.getRuntime().load(extractedNativeLib.toString());
        LOADED.add(key);
    }

    /**
     * Extracts the given resource and writes it to the specified file.
     * Note that this method fails silently.
     *
     * @param baseClass class to use as base class for the resource lookup
     * @param sourceResource resource name
     * @param targetFile target file
     */
    private static void extractResourceToFile(final Class<?> baseClass, final String sourceResource, final Path targetFile) {
        try {
            try (final InputStream in = baseClass.getResourceAsStream(sourceResource)) {
                Files.copy(in, targetFile, REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to extract library " + sourceResource, e);
        }
    }

    /**
     * Read project version, injected by Maven.
     *
     * @return project version or <code>unknown</code>, if not found.
     */
    private static String readProjectVersion() {
        try {
            final Properties properties = new Properties();
            properties.load(AudioPlayer.class.getResourceAsStream("project.properties"));
            return properties.getProperty("version", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

}
