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
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.javacv.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Encapsulates the clip exporting process.
 * This module is only for exporting clips.
 */
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

        final String inputPath = root.get("source_video").getAsString();

        final String outputDirPath = root.get("output_path").getAsString();

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


    private FFmpegFrameRecorder getEncoder(final File outputFile, final FFmpegFrameGrabber decoder) throws FFmpegFrameRecorder.Exception {
        final FFmpegFrameRecorder encoder = new FFmpegFrameRecorder(outputFile, decoder.getImageWidth(), decoder.getImageHeight(), 2);
        FFmpegUtil.configureEncoder(encoder, decoder, editingFlags);
        encoder.setVideoCodec(decoder.getVideoCodec()); // Codec copy.

        encoder.start(decoder.getFormatContext());
        return encoder;
    }

    private FFmpegFrameFilter[] getFilters(final FFmpegFrameGrabber decoder, final FFmpegFrameRecorder encoder) throws FFmpegFrameFilter.Exception {
        if (!editingFlags.contains(EditingFlag.PROCESS_SEGMENTS)) {
            return new FFmpegFrameFilter[0];
        }

        // Filters that might be applied if the flag is enabled.
        // TODO: Include audio filters
        final List<Filter> exportVideo = FilterManager.FILTER_MANAGER.getFilters(filter -> filter.getFilterRange() == FilterRange.EXPORT && filter.getFilterType() == FilterType.VIDEO);

        // I hate this.
        final EditInfo editInfo = new EditInfoBuilder()
                .setAspectRatio(decoder.getAspectRatio())
                .setFrameRate(decoder.getFrameRate())
                .setImageHeight(decoder.getImageHeight())
                .setImageWidth(decoder.getImageWidth())
                .setVideoBitrate(decoder.getVideoBitrate())
                .setVideoCodec(decoder.getVideoCodec())
                .setVideoCodecName(decoder.getVideoCodecName())
                .setPixelFormat(encoder.getPixelFormat())
                .createEditInfo();

        return new FFmpegFrameFilter[]{FFmpegUtil.populateVideoFilters(exportVideo, editInfo)};
    }

    public void exportClips(final ExportResolution resolution) {
        try {
            // Frame grabber to navigate & grab the selected clips.
            final FFmpegFrameGrabber decoder = new FFmpegFrameGrabber(inputPath);
            FFmpegUtil.configureDecoder(decoder); // base configuration of all encoder / decoder classes.
            decoder.setAudioStream(1);
            decoder.setImageWidth((int) (decoder.getImageWidth() * resolution.getRatio()));
            decoder.setImageHeight((int) (decoder.getImageHeight() * resolution.getRatio()));
            decoder.start();


            // List of sorted video clips, so the input grabber only has to move forwards in the stream.
            final List<VideoClip> sortedVideoClips = new ArrayList<>(videoClips);
            sortedVideoClips.sort(Comparator.comparingLong(VideoClip::getTimeStamp));

            /* Iterate through the sorted video clips and write them to disk */

            for (final VideoClip videoClip : sortedVideoClips) {
                final int segmentPosition = videoClips.indexOf(videoClip);
                // Write a new segment to disk
                final File segmentFile = new File(outputDirectory, String.format("segment %d.mp4", segmentPosition));

                // Recorder for the segment file. Configured in the same way the final recorder is configured, as to not lose quality.
                final FFmpegFrameRecorder encoder = getEncoder(segmentFile, decoder);
                // Possibly empty array of filter(s) which are processed in a chain if there are any.
                final FFmpegFrameFilter[] filters = getFilters(decoder, encoder);

                // Navigate to the clip.
                decoder.setVideoTimestamp(videoClip.getTimeStamp());

                // The end of the clip in microseconds, e.g. stamp + length
                final long endMicros = videoClip.getTimeStamp() + videoClip.getLength() + 1500000L; //1.5s (see: https://github.com/bytedeco/javacv/issues/1333)

                AVPacket packet;
                while (decoder.getTimestamp() <= endMicros && (packet = decoder.grabPacket()) != null) {
                    // See https://github.com/bytedeco/javacv/issues/1333
                    if (packet.pts() >= videoClip.getTimeStamp() && packet.pts() <= endMicros) {
                        encoder.recordPacket(packet);
                    }
                }


                // Close the filter(s) if there are any.
                for (final FFmpegFrameFilter filter : filters) {
                    filter.close();
                }

                // Close our local recorder.
                encoder.close();
            }   /* End video clip loop */

            decoder.close(); // Close the input grabber, free the resources.
        } catch (FrameGrabber.Exception | FrameFilter.Exception | FrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Default method for the video editor. Exports the video clips in full resolution & quality if chosen.
     */
    public void exportClips() {
        exportClips(ExportResolution.FULL);
    }


    // NOTE:: The values are converted to constants anyway. Express them this way for readability
    public enum ExportResolution {
        FULL(1),
        HALF(1 / 2D),
        QUARTER(1 / 4D),
        EIGHTH(1 / 8D),
        SIXTEENTH(1 / 16D);

        private final double ratio;

        ExportResolution(double ratio) {
            this.ratio = ratio;
        }

        public double getRatio() {
            return ratio;
        }
    }

}
