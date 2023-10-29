package de.yugata.easy.edits.audio;


/**
 * Data class for a clip of audio.
 */
public class AudioClip {


    /**
     * The audio clips start time in the edit (in ms).
     */
    private final int start;

    /**
     * The audio clips end in the edit (in ms).
     */
    private final int end;

    /**
     * The timestamp from which the audio is sourced.
     * <p>
     * TODO: in an ideal world, this would be replaced by the audio data itself.
     * But, with our current frontend, this is not possible.
     * Someday, I will convert the frontend from dart with flutter to kotlin with compose, and then we can just set the data here automatically.
     * Also, because kotlin and java interop is very easy :)
     */
    private final long timestamp;

    private final long clipLength;


    public AudioClip(int start, int end, long timestamp, long clipLength) {
        this.start = start;
        this.end = end;
        this.timestamp = timestamp;
        this.clipLength = (end - start) * 1000L;
    }

    public long getLength() {
        return clipLength;
    }

    public boolean uniqueOverlap(final AudioClip audioClip) {
        return audioClip != this && Math.max(getStart(), audioClip.getStart()) <= Math.min(getEnd(), audioClip.getEnd());
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
