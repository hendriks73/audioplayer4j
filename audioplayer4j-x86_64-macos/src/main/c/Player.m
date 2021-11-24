/*
 * =================================================
 * Copyright 2021 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */

#import "com_tagtraum_audioplayer4j_macos_AVFoundationPlayer.h"
#import <Foundation/Foundation.h>
#import <CoreMedia/CoreMedia.h>
#import <AVFoundation/AVFoundation.h>


jstring CreateJavaStringFromNSString(JNIEnv *env, NSString *nativeStr)
{
    if (nativeStr == NULL)
    {
        return NULL;
    }
    // Note that length returns the number of UTF-16 characters,
    // which is not necessarily the number of printed/composed characters
    jsize buflength = [nativeStr length];
    unichar buffer[buflength];
    [nativeStr getCharacters:buffer];
    jstring javaStr = (*env)->NewString(env, (jchar *)buffer, buflength);
    return javaStr;
}

void playerThrowIOExceptionIfError(JNIEnv *env, int err, const char * message) {
    if (err) {
#ifdef DEBUG
        printf("Errorcode: %i\n", err);
#endif
        if (err == fnfErr) {
            jclass excCls = (*env)->FindClass(env, "java/io/FileNotFoundException");
            (*env)->ThrowNew(env, excCls, message);
        } else {
            jclass excCls = (*env)->FindClass(env, "java/io/IOException");
            (*env)->ThrowNew(env, excCls, message);
        }
    }
}

void playerThrowUnsupportedAudioFileException(JNIEnv *env, const char * message) {
    jclass excCls = (*env)->FindClass(env, "javax/sound/sampled/UnsupportedAudioFileException");
    (*env)->ThrowNew(env, excCls, message);
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    open
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlongArray JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_open
  (JNIEnv *env, jobject object, jstring url) {

    AVPlayer *player = nil;
    jlongArray pointers;
    jobject javaPlayerReference = NULL;
    JavaVM* jvm = NULL;
    int status = 0;

    status = (*env)->GetJavaVM(env, &jvm);
    if (status != 0) {
        playerThrowIOExceptionIfError(env, status, "Failed to get JavaVM");
        return NULL;
    }

    const jchar *chars = (*env)->GetStringChars(env, url, NULL);
    NSString *urlName = [NSString stringWithCharacters:(UniChar *)chars
                                            length:(*env)->GetStringLength(env, url)];
    (*env)->ReleaseStringChars(env, url, chars);

#ifdef DEBUG
    NSLog(@"urlName:  %@", urlName);
#endif

    player = [AVPlayer playerWithURL: [NSURL URLWithString: urlName]];

#ifdef DEBUG
    NSLog(@"Status=%li", [player status]);
    NSLog(@"Error=%@", [player error]);
#endif

    if (![[[player currentItem] asset] isPlayable]) {
#ifdef DEBUG
        NSLog(@"Asset is NOT playable");
#endif
        playerThrowUnsupportedAudioFileException(env, "Asset is not playable.");
    } else {

        // busy wait ... :-(
        int timeout = 0;
        while (([player status] == AVPlayerStatusUnknown || [[player currentItem] status] == AVPlayerItemStatusUnknown) && timeout < 100) {
            [NSThread sleepForTimeInterval: 0.1];
#ifdef DEBUG
            NSLog(@"Player Status=%li", [player status]);
            NSLog(@"Player Error:  %@", [player error]);
            NSLog(@"Item Status=%li", [[player currentItem] status]);
            NSLog(@"Item Error:  %@", [[player currentItem] error]);
#endif
            timeout++;
        }

        if ([[player currentItem] status] != AVPlayerItemStatusReadyToPlay) {

#ifdef DEBUG
            NSLog(@"Item Error:  %@", [[player currentItem] error]);
            NSLog(@"failure reason:  %@", [[player currentItem] error].localizedFailureReason);
#endif

            if ([[player currentItem] error] != NULL) {
                playerThrowIOExceptionIfError(env, [[player currentItem] error].code, [[[player currentItem] error].localizedFailureReason cStringUsingEncoding: NSUTF8StringEncoding]);
            } else {
                playerThrowIOExceptionIfError(env, 1, "Failed to load media.");
            }
        } else {
            [player retain];
            [player prerollAtRate:1.0 completionHandler:nil];
            player.actionAtItemEnd = AVPlayerActionAtItemEndPause;

            // set up AVPlayerItemDidPlayToEndTimeNotification
            javaPlayerReference = (*env)->NewGlobalRef(env, object);

            jclass playerClass = (*env)->FindClass(env, "com/tagtraum/audioplayer4j/macos/AVFoundationPlayer");
            if (!playerClass) {
                playerThrowIOExceptionIfError(env, 1, "Failed to find AVFoundationPlayer class.");
                return NULL;
            }
            jmethodID mid = (*env)->GetMethodID(env, playerClass, "didPlayToEndTime", "()V");
            if (!mid) {
                playerThrowIOExceptionIfError(env, 1, "Failed to find didPlayToEndTime method.");
                return NULL;
            }

            NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
            id observer = [center addObserverForName:AVPlayerItemDidPlayToEndTimeNotification
                object:[player currentItem]
                queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification *note) {
                    JNIEnv *env;
                    int status = 0;
                    status = (*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL);
                    if (status == JNI_OK) {
                        if (javaPlayerReference == NULL) {
                            NSLog(@"javaPlayerReference is NULL");
                            return;
                        }
                        (*env)->CallVoidMethod(env, javaPlayerReference, mid);
                    } else {
                        NSLog(@"didPlayToEndTime failed, because AttachCurrentThread failed: %i", status);
                    }
                 }];

            pointers = (*env)->NewLongArray(env, 3);
            if (pointers == NULL) {
                playerThrowIOExceptionIfError(env, status, "Failed to allocate array");
            } else {
                jlong temp[3];
                temp[0] = (jlong)player;
                temp[1] = (jlong)javaPlayerReference;
                temp[2] = (jlong)observer;
                (*env)->SetLongArrayRegion(env, pointers, 0, 3, temp);
                return pointers;
            }
        }
    }
    return NULL;
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    start
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_start
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
    }
    [player play];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    stop
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_stop
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
    }
    [player pause];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    playImmediatelyAtRate
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_playImmediatelyAtRate
  (JNIEnv *env, jclass object, jlong pointer, jfloat rate) {
    if (@available(macOS 10.12, *)) {
      AVPlayer *player = (AVPlayer*)pointer;
      if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
      }
      [player playImmediatelyAtRate: rate];
    }
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    reset
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_reset
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
    }
    [player seekToTime: kCMTimeZero];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    isDone
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_isPlaying
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return false;
    }
    return [player rate] > 0;
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    setMuted
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_setMuted
  (JNIEnv *env, jclass object, jlong pointer, jboolean muted) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
    }
    player.muted = muted;
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    isMuted
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_isMuted
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return false;
    }
    return [player isMuted];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    setVolume
 * Signature: (JS)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_setVolume
  (JNIEnv *env, jclass object, jlong pointer, jfloat volume) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
    }
    player.volume = volume;
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getVolume
 * Signature: (J)S
 */
JNIEXPORT jfloat JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getVolume
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return 0;
    }
    return [player volume];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    setTime
 * Signature: (JJ)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_setTime
  (JNIEnv *env, jobject object, jlong pointer, jlong time) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return;
    }

    JavaVM* jvm = NULL;
    int status = 0;

    status = (*env)->GetJavaVM(env, &jvm);
    if (status != 0) {
        playerThrowIOExceptionIfError(env, status, "Failed to get JavaVM");
        return;
    }

    // set up AVPlayerItemDidPlayToEndTimeNotification
    jobject javaPlayerReference = (*env)->NewGlobalRef(env, object);

    jclass playerClass = (*env)->FindClass(env, "com/tagtraum/audioplayer4j/macos/AVFoundationPlayer");
    if (!playerClass) {
        playerThrowIOExceptionIfError(env, 1, "Failed to find AVFoundationPlayer class.");
        return;
    }
    jmethodID mid = (*env)->GetMethodID(env, playerClass, "fireTime", "(J)V");
    if (!mid) {
        playerThrowIOExceptionIfError(env, 1, "Failed to find fireTime(long) method.");
        return;
    }

    [player seekToTime: CMTimeMakeWithSeconds(time/1000.0, 1000)
        completionHandler: ^(BOOL finished) {

            CMTime cmTime = [player currentTime];
            jlong currentTime = (jlong)CMTimeGetSeconds(CMTimeMultiply(cmTime, 1000));

            JNIEnv *env;
            int status = 0;
            status = (*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL);
            if (status == JNI_OK) {
                if (javaPlayerReference == NULL) {
                    NSLog(@"javaPlayerReference is NULL");
                    return;
                }
                (*env)->CallVoidMethod(env, javaPlayerReference, mid, currentTime);
            } else {
                NSLog(@"fireTime(long) failed, because AttachCurrentThread failed: %i", status);
            }
            (*env)->DeleteGlobalRef(env, javaPlayerReference);
        }];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getTime
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getTime
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return 0L;
    }
    CMTime cmTime = [player currentTime];
    return (jlong)CMTimeGetSeconds(CMTimeMultiply(cmTime, 1000));
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getDuration
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getDuration
  (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return 0L;
    }
    AVPlayerItem *item = [player currentItem];
    CMTime cmTime = [[item asset] duration];
    if (CMTIME_IS_INDEFINITE(cmTime)) {
#ifdef DEBUG
        NSLog(@"Duration is kCMTimeIndefinite");
#endif
        return -1;
    }
    return (jlong)CMTimeGetSeconds(CMTimeMultiply(cmTime, 1000));
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    close
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_close
        (JNIEnv *env, jclass object, jlongArray pointers) {

#ifdef DEBUG
    NSLog(@"native close()");
#endif

    if (pointers == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "Cannot close null pointers");
        return;
    }

    jsize len = (*env)->GetArrayLength(env, pointers);
    if (len != 3) {
        jclass excCls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        (*env)->ThrowNew(env, excCls, "pointers must be long array with length 3");
        return;
    }

    jlong *p = (*env)->GetLongArrayElements(env, pointers, 0);

    id observer = (id) p[2];
    if (observer != NULL) {
        NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
        [center removeObserver: observer];
    }

    jobject javaPlayerReference = (jobject) p[1];
    if (javaPlayerReference != NULL) {
        (*env)->DeleteGlobalRef(env, javaPlayerReference);
    }

    AVPlayer *player = (AVPlayer*) p[0];
    if (player != NULL) {
        [player release];
    }

    (*env)->ReleaseLongArrayElements(env, pointers, p, 0);

#ifdef DEBUG
    NSLog(@"native close() Done.");
#endif
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getStatus
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getStatus
    (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return 0;
    }
    return (jint)[player status];
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getError
 * Signature: (J)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getError
    (JNIEnv *env, jclass object, jlong pointer) {
    AVPlayer *player = (AVPlayer*)pointer;
    if (player == NULL) {
        jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
        return NULL;
    }
    jstring error = NULL;
    NSError *ns_error = [player error];
    if (ns_error != NULL) {
        error = CreateJavaStringFromNSString(env, [ns_error description]);
    }
    return error;
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getTimeControlStatus
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getTimeControlStatus
        (JNIEnv *env, jclass object, jlong pointer) {
    // macOS 10.12 or later code path
    if (@available(macOS 10.12, *)) {
        AVPlayer *player = (AVPlayer*)pointer;
        if (player == NULL) {
            jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
            (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
            return 0;
        }
        return (jint)[player timeControlStatus];
    }
    return 0;
}

/*
 * Class:     com_tagtraum_audioplayer4j_macos_AVFoundationPlayer
 * Method:    getReasonForWaitingToPlay
 * Signature: (J)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_tagtraum_audioplayer4j_macos_AVFoundationPlayer_getReasonForWaitingToPlay
        (JNIEnv *env, jclass object, jlong pointer) {
    jstring reason = NULL;
    // macOS 10.12 or later code path
    if (@available(macOS 10.12, *)) {
        AVPlayer *player = (AVPlayer*)pointer;
        if (player == NULL) {
            jclass excCls = (*env)->FindClass(env, "java/lang/NullPointerException");
            (*env)->ThrowNew(env, excCls, "AVPlayer pointer is null");
            return NULL;
        }
         NSString *ns_reason = [player reasonForWaitingToPlay];
         if (ns_reason != NULL) {
             reason = CreateJavaStringFromNSString(env, ns_reason);
         }
    }
    return reason;
}


