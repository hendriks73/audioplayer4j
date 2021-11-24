/**
 * Java audio player.
 *
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 */
module tagtraum.audioplayer4j {
    requires transitive java.logging;
    requires transitive java.desktop;
    requires transitive java.prefs;
    requires javafx.media;
    requires javafx.swing;

    exports com.tagtraum.audioplayer4j;
}