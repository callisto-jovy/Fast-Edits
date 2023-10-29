package de.yugata.easy.edits.editor.video;


import de.yugata.easy.edits.audio.AudioClip;
import de.yugata.easy.edits.editor.Editor;
import de.yugata.easy.edits.editor.edit.EditInfo;
import de.yugata.easy.edits.editor.edit.EditInfoBuilder;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.filter.FilterManager;
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

/**
 * TODO: This needs documentation & a cleanup
 */
public class VideoEditor implements Editor {

    private final List<VideoClip> videoClips;


    /**
     * ToDO: make part of the constructor
     */
    private final List<AudioClip> audioClips = new ArrayList<>();


    /**
     * The Framegrabber which grabs the input video
     */
    private FFmpegFrameGrabber videoGrabber;

    /**
     * The video's input path
     */
    private final String videoPath;

    /**
     * The audio file's path
     */
    private final String overlayAudioPath;

    private final EnumSet<EditingFlag> editingFlags;

    private final List<FilterWrapper> filters;

    private final File outputFile;

    private final File segmentAudioFile;

    public VideoEditor(final String videoPath,
                       final String overlayAudioPath,
                       final File outputFile,
                       final List<VideoClip> videoClips,
                       final EnumSet<EditingFlag> flags,
                       final List<FilterWrapper> filters,
                       final long introStart,
                       final long introEnd,
                       final File workingDirectory) {

        this.videoPath = videoPath;
        this.overlayAudioPath = overlayAudioPath;
        this.videoClips = videoClips;
        this.editingFlags = flags;
        this.filters = filters;

        if (outputFile.exists()) {
            this.outputFile = new File(workingDirectory.getParent(), UUID.randomUUID() + outputFile.getName());
        } else {
            this.outputFile = outputFile;
        }

        this.segmentAudioFile = new File(workingDirectory, UUID.randomUUID() + ".wav");

        if (flags.contains(EditingFlag.PRINT_DEBUG)) {
            FFmpegLogCallback.set();
            av_log_set_level(AV_LOG_VERBOSE);
        }
    }


    /**
     * Initializes & configure the frame grabber for the input video.
     */
    private void initFrameGrabber() {
        if (videoGrabber == null) {
            try {
                this.videoGrabber = new FFmpegFrameGrabber(videoPath);
                FFmpegUtil.configureGrabber(videoGrabber);
                videoGrabber.start();

            } catch (FFmpegFrameGrabber.Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            try {
                videoGrabber.restart();
            } catch (FrameGrabber.Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    /**
     * Frees the resource allocated by the frame grabber
     */
    private void releaseFrameGrabber() {
        if (videoGrabber != null) {
            try {
                videoGrabber.close();
                this.videoGrabber = null;
            } catch (FrameGrabber.Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private FFmpegFrameRecorder createRecorder(final File outputFile, final EnumSet<EditingFlag> editingFlags, final FFmpegFrameGrabber inputGrabber) throws FFmpegFrameRecorder.Exception {
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

        recorder.setAudioOption("ac", "2"); // Downsample the 5.1 to stereo
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H265);
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_AC3); // Standard
        recorder.setFrameRate(inputGrabber.getFrameRate()); //
        recorder.setSampleRate(inputGrabber.getSampleRate()); // Sample rate from the audio source
        // Select the "highest" bitrate.
        recorder.setVideoBitrate(0); // max bitrate

        return recorder;
    }

    private void pushAudioFilter(FFmpegFrameGrabber videoGrabber, final FFmpegFrameFilter filter, final int n, final AudioClip audioClip) throws FFmpegFrameGrabber.Exception, FFmpegFrameFilter.Exception {
        videoGrabber.setAudioTimestamp(audioClip.getTimestamp());

        Frame frame;
        while ((frame = videoGrabber.grabSamples()) != null && (videoGrabber.getTimestamp() - audioClip.getTimestamp()) <= audioClip.getLength()) {
            filter.push(n, frame);
        }
    }


    private void combineAudioClips() throws FrameRecorder.Exception, FrameFilter.Exception, FFmpegFrameGrabber.Exception {
        // FIXME: In an ideal world, we would not need this. the data would already be in the audio clip.
        this.initFrameGrabber();

        // Audio recorder
        final FFmpegFrameRecorder audioRecorder = new FFmpegFrameRecorder(segmentAudioFile, 2);
        audioRecorder.setAudioOption("ac", "2"); // Downsample the 5.1 to stereo
        audioRecorder.setFrameRate(videoGrabber.getAudioFrameRate());
        audioRecorder.setSampleRate(videoGrabber.getSampleRate()); // Sample rate from the audio source
        audioRecorder.setSampleFormat(AV_SAMPLE_FMT_FLTP);
        audioRecorder.setAudioCodec(avcodec.AV_CODEC_ID_AC3);
        audioRecorder.start();

        // Filter to convert the supplied audio into the same format & sample rate as the recorder.
        final FFmpegFrameFilter convertAudioFilter = convertAudioFilter(audioRecorder);

        // Record the background audio.

        // Grabs the audio frames from the supplied audio
        final FFmpegFrameGrabber overlayAudioGrabber = new FFmpegFrameGrabber(overlayAudioPath);
        overlayAudioGrabber.setSampleFormat(AV_SAMPLE_FMT_FLTP);
        overlayAudioGrabber.start();

        // Feed the background audio into the single overlay filter.
        // This overlay filter will then be
        Frame audioFrame;




        // Sort the audio filters by their appearance



        // Check audio clip overlap
        for (final AudioClip audioClip : audioClips) {
            // Count the amount of overlays with the other audio clips.
            final int overlaps = (int) audioClips.stream().filter(clip -> clip.uniqueOverlap(audioClip)).count();
            // Audio filter which overlays audio and input audio if there is input audio given.
            final FFmpegFrameFilter overlayFilter = overlayAudioFilter(overlaps + 1, audioRecorder);


            for (int i = 0; i < audioClips.size(); i++) {
                final AudioClip comparisonClip = audioClips.get(i);
                // Overlap check.
                if (audioClip.uniqueOverlap(comparisonClip)) {

                    pushAudioFilter(videoGrabber, overlayFilter, i, comparisonClip);
                }

                // Remove the clip, as it has been layered already.
                audioClips.remove(comparisonClip);
            }

            // push the audio clip to the filter
            pushAudioFilter(videoGrabber, overlayFilter, overlaps + 1, audioClip);

            // pull from the fed overlay filter & record at the appropriate time.
            Frame frame;
            while ((frame = overlayFilter.pull()) != null) {

            }

            overlayFilter.close(); // Close the overlay filter.
        }
        convertAudioFilter.close();
        audioRecorder.close();
        this.releaseFrameGrabber();
    }


    public void edit() {

        this.initFrameGrabber();
        try {
            // Grabs the audio frames from the supplied audio
            final FFmpegFrameGrabber overlayAudioGrabber = new FFmpegFrameGrabber(overlayAudioPath);
            overlayAudioGrabber.setSampleFormat(AV_SAMPLE_FMT_FLTP);
            overlayAudioGrabber.start();

            // grabs audio frames from the concatenated sequence audio.
            final FFmpegFrameGrabber segmentAudioGrabber = new FFmpegFrameGrabber(segmentAudioFile);
            // segmentAudioGrabber.setSampleMode(FrameGrabber.SampleMode.FLOAT);
            segmentAudioGrabber.setSampleFormat(AV_SAMPLE_FMT_FLTP); // Does not read properly-
            segmentAudioGrabber.start();

            // Configure the recorder
            final FFmpegFrameRecorder recorder = createRecorder(outputFile, editingFlags, videoGrabber);
            recorder.setAudioCodec(segmentAudioGrabber.getAudioCodec());
            recorder.setSampleRate(segmentAudioGrabber.getSampleRate());
            recorder.setSampleFormat(segmentAudioGrabber.getSampleFormat());
            recorder.start();


            // Edit: I fucking hate this, we just pass the frame grabber in the fucking future...
            final EditInfo editInfo = new EditInfoBuilder()
                    .setEditTime(segmentAudioGrabber.getLengthInTime())
                    .setAudioCodec(overlayAudioGrabber.getAudioCodec())
                    .setAspectRatio(videoGrabber.getAspectRatio())
                    .setAudioChannels(overlayAudioGrabber.getAudioChannels())
                    .setAudioBitrate(overlayAudioGrabber.getAudioBitrate())
                    .setFrameRate(videoGrabber.getFrameRate())
                    .setImageHeight(videoGrabber.getImageHeight())
                    .setImageWidth(videoGrabber.getImageWidth())
                    .setAudioCodecName(overlayAudioGrabber.getAudioCodecName())
                    .setSampleFormat(overlayAudioGrabber.getSampleFormat())
                    .setVideoBitrate(videoGrabber.getVideoBitrate())
                    .setImageScalingFlags(videoGrabber.getImageScalingFlags())
                    .setSampleRate(overlayAudioGrabber.getSampleRate())
                    .setVideoCodec(videoGrabber.getVideoCodec())
                    .setVideoCodecName(videoGrabber.getVideoCodecName())
                    .setPixelFormat(recorder.getPixelFormat())
                    .createEditInfo();


            // Populate the filters
            FilterManager.FILTER_MANAGER.populateFilters(filters, editInfo);


            // optional: audio filter with chained  audio filters.
            final FFmpegFrameFilter simpleAudioFiler = simpleAudioFilter(recorder);

            // Audio filter which overlays audio and input audio if there is input audio given.
            final FFmpegFrameFilter overlayFilter = overlayAudioFilter(recorder);

            // Filter to convert the supplied audio into the same format & sample rate as the recorder.
            final FFmpegFrameFilter convertAudioFilter = convertAudioFilter(recorder);

            // optional: Frame filter with chained together video filters
            final FFmpegFrameFilter simpleVideoFiler = FFmpegUtil.populateVideoFilters(editInfo);

            /* Workflow */

            //  1. Record the segments video frames.
            //  2. Record the audio frames in a separate loop#

            /* video clip loop */

            for (final String inputPath : segmentPaths) {
                // grabber for the individual clip segment
                final FFmpegFrameGrabber segmentGrabber = baseSegmentGrabber(new File(inputPath), recorder, "h265_cuvid");

                // Optional: Populate the transition filters, we have to reconfigure them every time, as the offsets depend on it.
                final FFmpegFrameFilter transitionFilter = FFmpegUtil.populateTransitionFilters(editInfo);

                // Add the filters to a chain.
                final FFmpegFrameFilter[] filters = simpleVideoFiler == null ? new FFmpegFrameFilter[0] : transitionFilter == null ? new FFmpegFrameFilter[]{simpleVideoFiler} : new FFmpegFrameFilter[]{transitionFilter, simpleVideoFiler};

                // grab the video frames & send them to the filters
                Frame frame;
                while ((frame = segmentGrabber.grabImage()) != null) {
                    FFmpegUtil.pushToFilters(frame, recorder, filters);
                }

                // Close the transition filter, free the resources
                if (transitionFilter != null)
                    transitionFilter.close();

                segmentGrabber.close(); // Close the segment grabber, free the resources.
            }

            /* End video loop */


            Frame segmentAudio;
            while ((segmentAudio = segmentAudioGrabber.grabSamples()) != null) {
                // Push the background audio to [0], no volume decrease
                overlayFilter.push(0, segmentAudio);
            }

            Frame audioFrame;
            while ((audioFrame = overlayAudioGrabber.grabSamples()) != null) {
                // process the audio frame, convert the audio frame to our sample-rate, sample-format
                convertAudioFilter.push(audioFrame);

                Frame convertAudioFrame;
                if ((convertAudioFrame = convertAudioFilter.pull()) != null) {
                    FFmpegUtil.pushToFilterOrElse(convertAudioFrame, simpleAudioFiler, f -> {
                        try {
                            overlayFilter.push(1, f);
                        } catch (FFmpegFrameFilter.Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }

            // Pull from the overlay filter & record.
            Frame overlayFrame;
            while ((overlayFrame = overlayFilter.pull()) != null) {
                // Set the timestamp in the recorder.
                recorder.setTimestamp(overlayFrame.timestamp);
                recorder.record(overlayFrame);
            }


            /* End audio grabbing */

            /* Close & free resources */

            if (simpleVideoFiler != null)
                simpleVideoFiler.close();

            if (simpleAudioFiler != null)
                simpleAudioFiler.close();


            overlayFilter.close();
            overlayAudioGrabber.close();
            segmentAudioGrabber.close();

            recorder.close();
        } catch (FrameRecorder.Exception | FrameGrabber.Exception | FrameFilter.Exception e) {
            e.printStackTrace();
        } finally {
            this.releaseFrameGrabber();
        }
    }

}