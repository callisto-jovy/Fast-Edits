package de.yugata.easy.edits.editor.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.yugata.easy.edits.editor.edit.EditInfo;
import de.yugata.easy.edits.editor.edit.EditInfoBuilder;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.filter.Filter;
import de.yugata.easy.edits.filter.FilterManager;
import de.yugata.easy.edits.filter.FilterRange;
import de.yugata.easy.edits.filter.FilterType;
import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.javacv.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class ClipExporter {

    /**
     * The exporter's output directory.
     */
    private final File outputDirectory;
    /**
     * A list of {@code VideoClip} - the clips that will be exported.
     */
    private final List<VideoClip> videoClips;
    /**
     * String to the input material from which the clips are sourced.
     */
    private final String inputPath;

    /**
     * EnumSet of flags for the exporting process.
     */
    private final EnumSet<EditingFlag> editingFlags;

    public ClipExporter(final String inputPath, final File outputDirectory, final List<VideoClip> videoClips, final EnumSet<EditingFlag> editingFlags) {
        this.outputDirectory = outputDirectory;
        this.videoClips = videoClips;
        this.inputPath = inputPath;
        this.editingFlags = editingFlags;
    }

    /**
     * Reconstructs a new clip exporter from a given json string.
     *
     * @param json formatted json string that configures the exporter's state.
     * @return a new {@code ClipExporter} from the given json.
     */
    public static ClipExporter fromJson(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        final String inputPath = root.get("input_path").getAsString();

        final String outputDirPath = root.get("working_directory").getAsString();

        final JsonArray videoClips = root.getAsJsonArray("video_clips");
        final List<VideoClip> mappedVideoClips = new ArrayList<>();
        // Iterate through the json array of video clips and reconstruct a new one for every element.^
        videoClips.forEach(jsonElement -> mappedVideoClips.add(new VideoClip(jsonElement.getAsJsonObject())));

        //
        final JsonObject editingFlags = root.getAsJsonObject("editing_flags");
        final EnumSet<EditingFlag> mappedEditingFlags = EnumSet.noneOf(EditingFlag.class);
        // iterate through the json objects as a map & add all the editing flags if the value as a boolean == true.
        editingFlags.asMap().forEach((key, value) -> {
            if (value.getAsBoolean())
                mappedEditingFlags.add(EditingFlag.valueOf(key));
        });

        return new ClipExporter(inputPath, new File(outputDirPath), mappedVideoClips, mappedEditingFlags);
    }


    private FFmpegFrameRecorder createRecorder(final File outputFile, final FFmpegFrameGrabber inputGrabber) throws FFmpegFrameRecorder.Exception {
        final FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, inputGrabber.getImageWidth(), inputGrabber.getImageHeight(), 2);

        recorder.setFormat("mkv");

        // Preserve the color range for the HDR video files.
        // This lets us tone-map the hdr content later on if we want to.
        //TODO: get this from the grabber
        if (editingFlags.contains(EditingFlag.WRITE_HDR_OPTIONS)) {
            recorder.setVideoOption("color_range", "tv");
            recorder.setVideoOption("colorspace", "bt2020nc");
            recorder.setVideoOption("color_primaries", "bt2020");
            recorder.setVideoOption("color_trc", "smpte2084");
        }

        // best quality --> Produces big files
        if (editingFlags.contains(EditingFlag.BEST_QUALITY)) {
            recorder.setVideoQuality(12);
            recorder.setVideoOption("cq", "12");
            recorder.setOption("preset", "slow");
            recorder.setVideoOption("profile", "main10");
            recorder.setVideoOption("crf", "12");
            recorder.setOption("tune", "hq");
            recorder.setOption("bf", "2");
        }

        recorder.setFrameRate(inputGrabber.getFrameRate());
        recorder.setSampleRate(inputGrabber.getSampleRate());
        recorder.setVideoBitrate(inputGrabber.getVideoBitrate());

        recorder.start();

        return recorder;
    }


    public void exportClips() {
        try {
            // Frame grabber to navigate & grab the selected clips.
            final FFmpegFrameGrabber inputGrabber = new FFmpegFrameGrabber(inputPath);
            FFmpegUtil.configureGrabber(inputGrabber);
            inputGrabber.start();

            // List of sorted video clips, so the input grabber only has to move forwards in the stream.
            final List<VideoClip> sortedVideoClips = new ArrayList<>(videoClips);
            sortedVideoClips.sort(Comparator.comparingLong(VideoClip::getTimeStamp));

            /* Iterate through the sorted video clips and write them to disk */

            for (final VideoClip videoClip : sortedVideoClips) {
                final int segmentPosition = videoClips.indexOf(videoClip);
                // Write a new segment to disk
                final File segmentFile = new File(outputDirectory, String.format("segment %d.mp4", segmentPosition));

                // Recorder for the segment file. Configured in the same way the final recorder is configured, as to not lose quality.
                final FFmpegFrameRecorder recorder = createRecorder(segmentFile, inputGrabber);

                final FFmpegFrameFilter[] filters;
                // Filters that might be applied if the flag is enabled.
                if (editingFlags.contains(EditingFlag.PROCESS_SEGMENTS)) {
                    // TODO: Include audio filters
                    final List<Filter> exportVideo = FilterManager.FILTER_MANAGER.getFilters(filter -> filter.getFilterRange() == FilterRange.EXPORT && filter.getFilterType() == FilterType.VIDEO);

                    // I hate this.
                    final EditInfo editInfo = new EditInfoBuilder()
                            .setAspectRatio(inputGrabber.getAspectRatio())
                            .setFrameRate(inputGrabber.getFrameRate())
                            .setImageHeight(inputGrabber.getImageHeight())
                            .setImageWidth(inputGrabber.getImageWidth())
                            .setVideoBitrate(inputGrabber.getVideoBitrate())
                            .setVideoCodec(inputGrabber.getVideoCodec())
                            .setVideoCodecName(inputGrabber.getVideoCodecName())
                            .setPixelFormat(recorder.getPixelFormat())
                            .createEditInfo();

                    filters = new FFmpegFrameFilter[]{FFmpegUtil.populateVideoFilters(exportVideo, editInfo)};
                } else {
                    filters = new FFmpegFrameFilter[0];
                }

                // Navigate to the clip.
                inputGrabber.setVideoTimestamp(videoClip.getTimeStamp());

                Frame frame;
                while ((frame = inputGrabber.grab()) != null && (inputGrabber.getTimestamp() - videoClip.getTimeStamp()) <= videoClip.getLength()) {
                    FFmpegUtil.pushToFilters(frame, recorder, filters);
                }

                // Close the filter(s) if there are any.
                for (final FFmpegFrameFilter filter : filters) {
                    filter.close();
                }

                // Close our local recorder.
                recorder.close();
            }   /* End video clip loop */

            inputGrabber.close(); // Close the input grabber, free the resources.
        } catch (FrameGrabber.Exception | FrameFilter.Exception | FrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }


}
