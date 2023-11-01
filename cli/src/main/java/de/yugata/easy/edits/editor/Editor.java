package de.yugata.easy.edits.editor;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.yugata.easy.edits.audio.AudioAnalyser;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.editor.video.VideoEditor;
import de.yugata.easy.edits.editor.video.VideoEditorBuilder;
import de.yugata.easy.edits.filter.FilterWrapper;
import de.yugata.easy.edits.model.CLIArgs;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class Editor {

    /**
     * Editor instance, no need to instantiate a new editor class
     */
    public static final Editor INSTANCE = new Editor();

    /**
     * A queue with all the time's between the beats. Supplied from the audio analyser after the analysis is done.
     */
    private final List<Double> timeBetweenBeats = new ArrayList<>();

    /**
     * A List with all the user's time stamps.
     */
    private final List<Long> timeStamps = new ArrayList<>();

    /**
     * The application's working directory.
     */
    public static final File WORKING_DIRECTORY = new File("editor_temp");

    static {
        WORKING_DIRECTORY.mkdir();
    }

    /**
     * An Enumset with the {@link EditingFlag} that will be passed to the editor
     */
    private final EnumSet<EditingFlag> editingFlags;

    private final List<String> filters = new ArrayList<>();


    /* Editor settings */

    //TODO: move to editing flags
    private long introStart = -1, intoEnd = -1;


    /**
     * Default constructor, creates a working temp.
     */
    public Editor() {
        this.editingFlags = EnumSet.of(EditingFlag.BEST_QUALITY, EditingFlag.WRITE_HDR_OPTIONS);
    }

    /**
     * Performs the audio analysis on the given audio input
     * with the given peak threshold & the ms-Threshold.
     * Clears the queue and adds the new queue with new beats.
     */
    public void runAudioAnalysis() {
        this.timeBetweenBeats.clear();
        this.timeBetweenBeats.addAll(AudioAnalyser.analyseBeats(CLIArgs.getAudioInput(), CLIArgs.getPeakThreshold(), CLIArgs.getMsThreshold()));
        System.out.println("We need a total of " + timeBetweenBeats.size() + " segments.");
        System.out.println("Avg. time between beats (ms): " + timeBetweenBeats.stream().reduce(Double::sum).orElse(0d) / timeBetweenBeats.size());
    }

    /**
     * Runs the editing process.
     * Throws an IllegalArgumentException if the beat queue is empty.
     * Then edits the video with the given inputs.
     */
    public void runEditing() {
        if (timeBetweenBeats.isEmpty()) {
            throw new IllegalArgumentException("The audio has not been analysed or no beats have been detected, in that case adjust the threshold.");
        }

        final File inputFile = new File(CLIArgs.getInput());
        final File outputFile = new File(inputFile.getName() + "_edit.mp4");

        final List<FilterWrapper> filterWrappers = new ArrayList<>();
        //TODO: Add filters

        final VideoEditor editor = new VideoEditorBuilder()
                .setVideoPath(CLIArgs.getInput())
                .setAudioPath(CLIArgs.getAudioInput())
                .setOutputFile(outputFile)
                .setFlags(editingFlags)
                .setIntroStart(introStart)
                .setFilters(filterWrappers)
                .setIntroEnd(intoEnd)
                .setVideoClips(new ArrayList<>(timeStamps))
                .createVideoEditor();

        editor.edit();
    }


    /**
     * Checks whether the editing process is possible.
     *
     * @return true if all arguments are set & the timeBetweenBeats is not empty. Otherwise, false.
     */
    public boolean editingPossible() {
        return CLIArgs.checkArguments().isEmpty() && !timeBetweenBeats.isEmpty();
    }

    /**
     * Adds a new timestamp in the next free slot. If that is not possible, the stamp will be appended to the list.
     *
     * @param stamp the time stamp to add.
     */
    public void addTimeStamp(final long stamp) {
        //THIS IS for functionality with the new editor ui. Any open spot created by whatsoever will be filled.
        for (int i = 0; i < timeStamps.size(); i++) {
            if (timeStamps.get(i) == null) {
                timeStamps.set(i, stamp);
                return;
            }
        }

        this.timeStamps.add(stamp);
    }

    /**
     * Removes a time stamp at a given index.
     *
     * @param index the index to set the time stamp to null. This marks the index as overridable.
     */
    public void removeStampAt(final int index) {
        if (index >= 0 && index <= timeStamps.size()) {
            timeStamps.set(index, null);
        }
    }

    /**
     * Removes the last timestamp (size -1) with a range check.
     */
    public void removeLastTimeStamp() {
        if (timeStamps.size() - 1 >= 0) {
            this.timeStamps.remove(timeStamps.size() - 1);
        }
    }

    /**
     * @return the size of the beats list size.
     */
    public int beats() {
        return timeBetweenBeats.size();
    }

    /**
     * @return the size of the time stamp list.
     */
    public int stamps() {
        return timeStamps.size();
    }

    /**
     * gets the timestamp at a given index with a range check.
     *
     * @param index the index to get.
     * @return the timestamp at i.
     */
    public Long timeStampAt(final int index) {
        if (index > timeStamps.size() || index < 0) {
            return null;
        }
        return timeStamps.get(index);
    }

    /**
     * Adds an editing flags or removes it if the flag has already been set
     *
     * @param editingFlag the flag to set / unset
     */
    public void addOrRemoveFlag(final EditingFlag editingFlag) {
        if (editingFlags.contains(editingFlag)) {
            editingFlags.remove(editingFlag);
        } else {
            editingFlags.add(editingFlag);
        }
    }

    public JsonObject toJson() {
        final JsonObject root = new JsonObject();
        final JsonArray jsonStamps = new JsonArray();
        final JsonArray jsonBeats = new JsonArray();

        timeBetweenBeats.forEach(aDouble -> jsonBeats.add(aDouble));
        timeStamps.forEach(aLong -> jsonStamps.add(aLong));

        root.addProperty("intro_start", introStart);
        root.addProperty("intro_end", intoEnd);

        root.add("time_stamps", jsonStamps);
        root.add("beat_times", jsonBeats);


        return root;
    }

    public void fromJson(final JsonObject root) {
        final JsonArray jsonStamps = root.getAsJsonArray("time_stamps");
        final JsonArray jsonBeats = root.getAsJsonArray("beat_times");
        final JsonArray jsonFilters = root.getAsJsonArray("filters");

        jsonFilters.forEach(jsonElement -> filters.add(jsonElement.getAsString()));
        jsonStamps.forEach(jsonElement -> timeStamps.add(jsonElement.getAsLong()));
        jsonBeats.forEach(jsonElement -> timeBetweenBeats.add(jsonElement.getAsDouble()));

        setIntoEnd(root.get("intro_end").getAsLong());
        setIntroStart(root.get("intro_start").getAsLong());

        final JsonObject editingOptions = root.getAsJsonObject("editing_flags");
        editingFlags.clear();
        editingOptions.asMap().forEach((key, value) -> {
            if (value.getAsBoolean())
                editingFlags.add(EditingFlag.valueOf(key));
        });
    }


    public double getIntroStart() {
        return introStart;
    }

    public void setIntroStart(long introStart) {
        this.introStart = introStart;
    }

    public double getIntoEnd() {
        return intoEnd;
    }

    public void setIntoEnd(long intoEnd) {
        this.intoEnd = intoEnd;
    }

    public EnumSet<EditingFlag> getEditingFlags() {
        return editingFlags;
    }
}
