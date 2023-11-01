package de.yugata.easy.edits.editor.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.filter.FilterWrapper;

import java.io.File;
import java.util.*;

public class VideoEditorBuilder {

    private List<FilterWrapper> filters = new ArrayList<>();
    private String videoPath;
    private String audioPath;
    private File outputFile;
    private List<VideoClip> videoClips = new ArrayList<>();
    private EnumSet<EditingFlag> flags = EnumSet.noneOf(EditingFlag.class);
    private File workingDirectory;


    public VideoEditorBuilder setVideoPath(String videoPath) {
        this.videoPath = videoPath;
        return this;
    }

    public VideoEditorBuilder setAudioPath(String audioPath) {
        this.audioPath = audioPath;
        return this;
    }

    public VideoEditorBuilder setOutputFile(File outputFile) {
        this.outputFile = outputFile;
        return this;
    }

    public VideoEditorBuilder setVideoClips(List<VideoClip> videoClips) {
        this.videoClips = videoClips;
        return this;
    }

    public VideoEditorBuilder setFlags(EnumSet<EditingFlag> flags) {
        this.flags = flags;
        return this;
    }

    public VideoEditorBuilder setFilters(List<FilterWrapper> filters) {
        this.filters = filters;
        return this;
    }

    public VideoEditorBuilder setWorkingDirectory(File workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }


    //TODO: Maybe move to filter wrapper
    public VideoEditor fromJson(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject editorState = root.getAsJsonObject("editor_state");
        final JsonArray filters = editorState.getAsJsonArray("filters");
        final JsonArray videoClips = editorState.getAsJsonArray("video_clips");
        final JsonObject editingFlags = editorState.getAsJsonObject("editing_flags");


        final List<FilterWrapper> mappedFilters = new ArrayList<>();
        filters.forEach(jsonElement -> mappedFilters.add(new FilterWrapper(jsonElement.getAsJsonObject())));

        final List<VideoClip> mappedVideoClips = new ArrayList<>();
        videoClips.forEach(jsonElement -> mappedVideoClips.add(new VideoClip(jsonElement.getAsJsonObject())));

        final EnumSet<EditingFlag> mappedEditingFlags = EnumSet.noneOf(EditingFlag.class);

        editingFlags.asMap().forEach((key, value) -> {
            if (value.getAsBoolean())
                mappedEditingFlags.add(EditingFlag.valueOf(key));
        });


        final String sourceVideo = root.get("source_video").getAsString();
        final String sourceAudio = root.get("source_audio").getAsString();
        final String outputPath = root.get("output_path").getAsString();
        final String workingPath = root.get("working_path").getAsString();

        return setVideoPath(sourceVideo)
                .setWorkingDirectory(new File(workingPath))
                .setOutputFile(new File(outputPath))
                .setAudioPath(sourceAudio)
                .setFilters(mappedFilters)
                .setFlags(mappedEditingFlags)
                .setVideoClips(mappedVideoClips)
                .createVideoEditor();
    }


    public VideoEditor createVideoEditor() {
        return new VideoEditor(videoPath, audioPath, outputFile, videoClips, flags, filters, workingDirectory);
    }
}