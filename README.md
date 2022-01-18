[![LGPL 2.1](https://img.shields.io/badge/License-LGPL_2.1-blue.svg)](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.tagtraum/audioplayer4j/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.tagtraum/audioplayer4j)
[![Build and Test](https://github.com/hendriks73/audioplayer4j/workflows/Build%20and%20Test/badge.svg)](https://github.com/hendriks73/audioplayer4j/actions)
[![CodeCov](https://codecov.io/gh/hendriks73/audioplayer4j/branch/main/graph/badge.svg?token=IBVAHZW5DZ)](https://codecov.io/gh/hendriks73/audioplayer4j/branch/main)


# audioplayer4j

Simply plays audio.

## Installation

*audioplayer4j* is released via [Maven](https://maven.apache.org).
You can install it using the following dependency:

```xml
<dependencies>
    <dependency>
        <groupId>com.tagtraum</groupId>
        <artifactId>audioplayer4j-complete</artifactId>
    </dependency>
</dependencies>
```

Without further packages, *audioplayer4j* works very well on macOS and,
but has only mediocre codec (audio format) support on other platforms.
That said, *audioplayer4j* plays very well with others. You can
play a wide variety for audio formats by installing a suitable
javax.sound.sampled package or JavaFX modules. For details, please see
below.

## Usage

To playback an audio file, simply call the `AudioPlayer`'s `play(uri)`
method:

```java
AudioPlayer.play(someURI);
```

Playback will start asynchronously.

Here's a more comprehensive example: 

```java
import com.tagtraum.audioplayer4j.*;
import java.util.concurrent.CountDownLatch;
import java.net.URI;

public class AudioplayerExample {
    
    public static void main(final String[] args) throws Exception {
        
        // open a player object for the given URI
        // do so, with try-resource management        
        try (final AudioPlayer player = AudioPlayerFactory.open(new URI(args[0]))) {

            // add a listener, so that we are notified,
            // once playback has stopped.            
            final CountDownLatch finished = new CountDownLatch(1);
            player.addAudioPlayerListener(new AudioPlayerListener() {
                @Override
                public void started(final AudioPlayer audioPlayer, final URI uri) {
                }

                @Override
                public void finished(final AudioPlayer audioPlayer, final URI uri) {
                    finished.countDown();
                }
            });

            // start playback        
            player.play();
            
            // wait until playback has finished.        
            finished.await();
        }
    }
}
```

If you'd like to be notified about playback process, pause events etc.,
just add a corresponding `PropertyListener`. Note that all events are
posted on the event dispatch thread (EDT).


## Leveraging javax.sound.sampled Packages 
                                     
The Java sampled sound API uses a service provider architecture, which can be implemented
by third parties (see [javax.sound.sampled.spi](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/sound/sampled/spi/package-summary.html)).
Any such providers may be picked up and used for playback by *audioplayer4j*.

Examples are:

- [FFSampledSP](https://github.com/hendriks73/ffsampledsp), an FFmpeg based provider (Ubuntu, macOS, Windows)
- [CASampledSP](https://github.com/hendriks73/casampledsp), a Core Audio-based provider (macOS only)

For example, to add *FFSampledSP*, simply use this dependency:

```xml
<dependencies>
    <dependency>
        <groupId>com.tagtraum</groupId>
        <artifactId>ffsampledsp-complete</artifactId>
    </dependency>
</dependencies>
```

## Taking Advantage of JavaFX

In order to allow *audioplayer4j* to utilize JavaFX libraries,
you may also want to add the following dependencies:

```xml
<dependencies>
    <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-base</artifactId>
    </dependency>
    <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-swing</artifactId>
    </dependency>
    <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-media</artifactId>
    </dependency>
    <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-graphics</artifactId>
    </dependency>
</dependencies>
```

## Java Module

*audioplayer4j* is shipped as a Java module
(see [JPMS](https://en.wikipedia.org/wiki/Java_Platform_Module_System))
with the name `tagtraum.audioplayer4j`.


## API

You can find the complete API [here](https://hendriks73.github.io/audioplayer4j/).
                       