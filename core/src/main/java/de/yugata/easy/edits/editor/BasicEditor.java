package de.yugata.easy.edits.editor;

import de.yugata.easy.edits.editor.edit.EditInfo;
import de.yugata.easy.edits.editor.edit.EditInfoBuilder;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.filter.FilterManager;
import de.yugata.easy.edits.filter.FilterWrapper;
import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.javacv.*;

import java.io.File;
import java.util.EnumSet;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_VERBOSE;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

public class BasicEditor implements Editor {

    private final FFmpegFrameGrabber videoGrabber;
    private final String audioPath;
    private final FFmpegFrameRecorder recorder;
    private final List<FilterWrapper> filters;

    private final long editLength;

    public BasicEditor(FFmpegFrameGrabber videoGrabber, String audioPath, FFmpegFrameRecorder recorder, List<FilterWrapper> filters, EnumSet<EditingFlag> editingFlags, long editLength) {
        this.videoGrabber = videoGrabber;
        this.audioPath = audioPath;
        this.recorder = recorder;
        this.filters = filters;
        this.editLength = editLength;

        if (editingFlags.contains(EditingFlag.PRINT_DEBUG)) {
            FFmpegLogCallback.set();
            av_log_set_level(AV_LOG_VERBOSE);
        }
        assert videoGrabber != null && audioPath != null && !audioPath.isEmpty() && recorder != null;
    }

    public void editFootage(final List<String> paths, final String segmentGrabberVideoCodec) throws FrameGrabber.Exception, FrameRecorder.Exception, FrameFilter.Exception {

        // Grabs the audio frames from the supplied audio
        final FFmpegFrameGrabber audioGrabber = new FFmpegFrameGrabber(audioPath);
        audioGrabber.setSampleRate(recorder.getSampleRate());
        audioGrabber.setSampleFormat(recorder.getSampleFormat());
        audioGrabber.start();


        // Edit: I fucking hate this, we just pass the frame grabber in the fucking future...
        final EditInfo editInfo = new EditInfoBuilder()
                .setEditTime(editLength)
                .setAudioCodec(audioGrabber.getAudioCodec())
                .setAspectRatio(videoGrabber.getAspectRatio())
                .setAudioChannels(audioGrabber.getAudioChannels())
                .setAudioBitrate(audioGrabber.getAudioBitrate())
                .setFrameRate(videoGrabber.getFrameRate())
                .setImageHeight(videoGrabber.getImageHeight())
                .setImageWidth(videoGrabber.getImageWidth())
                .setAudioCodecName(audioGrabber.getAudioCodecName())
                .setSampleFormat(audioGrabber.getSampleFormat())
                .setVideoBitrate(videoGrabber.getVideoBitrate())
                .setImageScalingFlags(videoGrabber.getImageScalingFlags())
                .setSampleRate(audioGrabber.getSampleRate())
                .setVideoCodec(videoGrabber.getVideoCodec())
                .setVideoCodecName(videoGrabber.getVideoCodecName())
                .setPixelFormat(recorder.getPixelFormat())
                .createEditInfo();


        // Populate the filters
        FilterManager.FILTER_MANAGER.populateFilters(filters, editInfo);


        // optional: audio filter with chained  audio filters.
        final FFmpegFrameFilter simpleAudioFiler = simpleAudioFilter(recorder);

        // Audio filter which overlays audio and input audio if there is input audio given.
        final FFmpegFrameFilter overlayFilter = overlayAudioFilter(5, recorder);

        // Filter to convert the supplied audio into the same format & sample rate as the recorder.
        final FFmpegFrameFilter convertAudioFilter = convertAudioFilter(recorder);

        // optional: Frame filter with chained together video filters
        final FFmpegFrameFilter simpleVideoFiler = FFmpegUtil.populateVideoFilters(editInfo);


        for (final String inputPath : paths) {
            // grabber for the individual clip segment
            final FFmpegFrameGrabber segmentGrabber = baseSegmentGrabber(new File(inputPath), recorder, segmentGrabberVideoCodec);

            // Safety mechanism.
            if (!segmentGrabber.hasAudio() && !segmentGrabber.hasVideo()) {
                continue;
            }


            // Optional: Populate the transition filters, we have to reconfigure them every time, as the offsets depend on it.
            final FFmpegFrameFilter transitionFilter = FFmpegUtil.populateTransitionFilters(editInfo);

            // Add the filters to a chain.
            final FFmpegFrameFilter[] filters = simpleVideoFiler == null ? new FFmpegFrameFilter[0] : transitionFilter == null ? new FFmpegFrameFilter[]{simpleVideoFiler} : new FFmpegFrameFilter[]{transitionFilter, simpleVideoFiler};

            /* Workflow */
            //  1. Record the segments video frames.
            //  2. Record the audio frames in a separate loop

            // Take note of the recorder timestamp, so we can adjust the audio timestamps accordingly later
            final int recorderFrameNumber = recorder.getFrameNumber();

            // grab the video frames & send them to the filters
            Frame frame;
            while ((frame = segmentGrabber.grabImage()) != null) {
                FFmpegUtil.pushToFilters(frame, recorder, filters);
            }

            final int postVideoFrameNumber = recorder.getFrameNumber();


            // Close the transition filter, free the resources
            if (transitionFilter != null)
                transitionFilter.close();

            // Seek back
            recorder.setFrameNumber(recorderFrameNumber);

            /* Audio frame processing */
            if (segmentGrabber.hasAudio()) { // The segment has audio, we have to overlay the background music.
                segmentGrabber.setTimestamp(0); // Seek back

                // Grab all the segment audio samples, as well as the audio grabber samples.
                // We terminate if either segment audio == null (no more items in the stream) or the audio grabber has no audio left.
                // In either case, we don't record audio anymore.

                Frame segmentAudio;
                while ((segmentAudio = segmentGrabber.grabSamples()) != null) {

                    // Push the background audio to [0], no volume decrease
                    overlayFilter.push(0, segmentAudio);
                }

                // Add as many audio frames as are needed for the segment.
                final long startTime = audioGrabber.getTimestamp(); // take note of our starting time, so that we know how many microseconds have passed

                Frame audioFrame;
                while (audioGrabber.getTimestamp() - startTime <= segmentGrabber.getLengthInTime() && (audioFrame = audioGrabber.grabSamples()) != null) {
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
                    recorder.record(overlayFrame);
                }
            } else { // no audio stream, just record the background music

                // Add as many audio frames as are needed for the segment.
                final long startTime = audioGrabber.getTimestamp(); // take note of our starting time, so that we know how many microseconds have passed

                Frame audioFrame;
                while (audioGrabber.getTimestamp() - startTime <= segmentGrabber.getLengthInTime() && (audioFrame = audioGrabber.grabSamples()) != null) {
                    recorder.record(audioFrame);
                }
            }

            // Safety mechanism, in the case that no audio has been recorded.
            recorder.setFrameNumber(postVideoFrameNumber);

            segmentGrabber.close();
        }


        if (simpleVideoFiler != null)
            simpleVideoFiler.close();

        if (simpleAudioFiler != null)
            simpleAudioFiler.close();


        overlayFilter.close();
        audioGrabber.close();
    }
}
