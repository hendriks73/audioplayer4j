/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.tagtraum.audioplayer4j.javafx;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides some utility methods to use JavaFX from a "regular" Java application.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
public class JavaFXUtilities {

    private static final Logger LOG = Logger.getLogger(JavaFXUtilities.class.getName());

    public static void invokeLater(final Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            try {
                runnable.run();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            }
        } else Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.toString(), e);
            }
        });
    }

    public static <T> T invokeAndWait(final Callable<T> callable) throws ExecutionException {
        if (Platform.isFxApplicationThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        } else {
            final Result<T> result = new Result<>();
            Platform.runLater(() -> {
                try {
                    result.setReturnValue(callable.call());
                } catch (Exception e) {
                    result.setException(e);
                }
            });
            synchronized (result) {
                while (!result.isCalled()) {
                    try {
                        result.wait(1000);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
            if (result.getException() == null) return result.getReturnValue();
            throw new ExecutionException(result.getException());
        }
    }

    private static class Result<T> {
        private Exception exception;
        private T returnValue;
        private boolean called;

        public synchronized Exception getException() {
            return exception;
        }

        public synchronized T getReturnValue() {
            return returnValue;
        }

        public synchronized boolean isCalled() {
            return called;
        }

        public synchronized void setException(Exception exception) {
            this.exception = exception;
            setCalled(true);
        }

        public synchronized void setReturnValue(final T returnValue) {
            this.returnValue = returnValue;
            setCalled(true);
        }

        private synchronized void setCalled(final boolean called) {
            this.called = called;
            this.notifyAll();
        }
    }
}
