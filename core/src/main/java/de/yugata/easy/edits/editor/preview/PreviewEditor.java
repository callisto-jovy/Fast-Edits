package de.yugata.easy.edits.editor.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.yugata.easy.edits.editor.BasicEditor;
import de.yugata.easy.edits.editor.BasicEditorBuilder;
import de.yugata.easy.edits.editor.Editor;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.editor.video.VideoClip;
import de.yugata.easy.edits.filter.FilterWrapper;
import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.bytedeco.ffmpeg.global.avutil.*;

public class PreviewEditor implements Editor {

    private final String videoPath;

    private final File workingDirectory;
    private final EnumSet<EditingFlag> editingFlags;

    private final List<FilterWrapper> filters;

    public PreviewEditor(final String videoPath, final File workingDirectory, final EnumSet<EditingFlag> flags, final List<FilterWrapper> filters) {
        this.videoPath = videoPath;
        this.workingDirectory = workingDirectory;
        this.editingFlags = flags;
        this.filters = filters;

        if (flags.contains(EditingFlag.PRINT_DEBUG)) {
            FFmpegLogCallback.set();
            av_log_set_level(AV_LOG_VERBOSE);
        }
    }

    public static PreviewEditor fromJson(final JsonObject root) {
        /* Base attributes */
        final String sourceVideo = root.get("source_video").getAsString();
        final String workingPath = root.get("working_path").getAsString();

        /* Filters */
        final JsonArray filters = root.getAsJsonArray("filters");

        final List<FilterWrapper> mappedFilters = new ArrayList<>();
        filters.forEach(jsonElement -> mappedFilters.add(new FilterWrapper(jsonElement.getAsJsonObject())));


        /* Editing flags */

        final JsonObject editingFlags = root.getAsJsonObject("editing_flags");
        final EnumSet<EditingFlag> mappedEditingFlags = EnumSet.noneOf(EditingFlag.class);

        editingFlags.asMap().forEach((key, value) -> {
            if (value.getAsBoolean()) mappedEditingFlags.add(EditingFlag.valueOf(key));
        });

        return new PreviewEditor(sourceVideo, new File(workingPath), mappedEditingFlags, mappedFilters);
    }

    private void configureRecorder(final FFmpegFrameRecorder recorder, final FFmpegFrameGrabber grabber) {
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

        recorder.setAudioOption("ac", "2"); // Downsample the 5.1 to stereo

        recorder.setVideoQuality(18);
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.setSampleRate(grabber.getSampleRate());
        recorder.setFrameRate(grabber.getFrameRate());

        recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC); // Standard
        recorder.setVideoCodecName("h264_nvenc");
    }

    private void configureFrameGrabber(final FFmpegFrameGrabber grabber) throws FFmpegFrameGrabber.Exception {
        FFmpegUtil.configureGrabber(grabber);
        grabber.setImageWidth(grabber.getImageWidth() / 8);
        grabber.setImageHeight(grabber.getImageHeight() / 8);
        grabber.setVideoCodecName("hevc_cuvid");
    }


    public String generatePreview(final VideoClip videoClip) {

        try (final FFmpegFrameGrabber videoGrabber = new FFmpegFrameGrabber(videoPath)) {
            this.configureFrameGrabber(videoGrabber);

            // idk why this is needed, it works in the editor, the code is almost the same, but hey, at least it works now.
            // hours_wasted=2
            if (videoClip.isMuteAudio()) {
                videoGrabber.setAudioStream(Integer.MAX_VALUE);
            }

            videoGrabber.start();

            videoGrabber.setTimestamp(videoClip.getTimeStamp());

            final File previewFile = new File(workingDirectory, "clip_" + UUID.randomUUID() + ".mkv");

            final FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(previewFile, videoGrabber.getImageWidth(), videoGrabber.getImageHeight(), 2);
            this.configureRecorder(recorder, videoGrabber);
            recorder.start();

            Frame frame;
            while (videoGrabber.getTimestamp() - videoClip.getTimeStamp() < videoClip.getLength() && (frame = videoGrabber.grab()) != null) {
                recorder.record(frame);
            }

            recorder.close();
            return previewFile.getAbsolutePath();
        } catch (FrameGrabber.Exception | FrameRecorder.Exception e) {
            e.printStackTrace();
            return "";
        }
    }


    public String editPreviews(final List<String> previewPaths, final String audioPath) {
        final File editOutput = new File(workingDirectory, "preview_" + UUID.randomUUID() + ".mkv");

        // get image width & height

        try {
            final FFmpegFrameGrabber videoGrabber = new FFmpegFrameGrabber(videoPath);
            this.configureFrameGrabber(videoGrabber);
            videoGrabber.start();

            // Configure the recorder
            final FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(editOutput, videoGrabber.getImageWidth(), videoGrabber.getImageHeight(), 2);
            this.configureRecorder(recorder, videoGrabber);
            recorder.start();


            final BasicEditor basicEditor = new BasicEditorBuilder()
                    .setAudioPath(audioPath)
                    .setEditingFlags(editingFlags)
                    .setFilters(filters)
                    .setRecorder(recorder)
                    .setVideoGrabber(videoGrabber)
                    .setEditLength(getEditLength(previewPaths))
                    .createBasicEditor();

            basicEditor.editFootage(previewPaths, "h264_nvenc");

            recorder.close();
            videoGrabber.close();
        } catch (FrameRecorder.Exception | FrameGrabber.Exception | FrameFilter.Exception e) {
            e.printStackTrace();
            return "";
        }
        return editOutput.getAbsolutePath();
    }


}
