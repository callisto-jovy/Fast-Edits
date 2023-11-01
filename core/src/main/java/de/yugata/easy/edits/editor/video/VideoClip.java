package de.yugata.easy.edits.editor.video;

import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

public class VideoClip {

    private final long timeStamp;
    private final long length;
    private final boolean muteAudio;

    public VideoClip(final JsonObject jsonElement) {
        this.timeStamp = jsonElement.get("time_stamp").getAsLong();
        this.muteAudio = jsonElement.get("mute_audio").getAsBoolean();
        // Passed length is in millis, so we have to convert it to micros.
        this.length = 1000L * (jsonElement.has("clip_length") ? jsonElement.get("clip_length").getAsLong() : -1);
    }


    public long getLength() {
        return length;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public boolean isMuteAudio() {
        return muteAudio;
    }
}
