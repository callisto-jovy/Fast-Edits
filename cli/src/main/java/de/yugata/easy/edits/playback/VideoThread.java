package de.yugata.easy.edits.playback;

import de.yugata.easy.edits.editor.Editor;
import de.yugata.easy.edits.model.CLIArgs;
import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import static org.opencv.imgproc.Imgproc.FONT_HERSHEY_PLAIN;

public class VideoThread extends Thread {


    /**
     * Const. Frame Converter
     */
    public static final OpenCVFrameConverter<Mat> FRAME_CONVERTER = new OpenCVFrameConverter.ToMat();

    /**
     * Const. Point to 50, 50 which will be the text's origin
     */
    public static final Point TEXT_ORIGIN = new Point(50, 50);

    /**
     * Const. Scalar with 255, 255, 255 = r,g,b == white
     */
    public static final Scalar SCALAR_WHITE = new Scalar(255, 255, 255);

    /**
     * The threads internal framegrabber which grabs continuous frames from the provided input-
     */
    private FFmpegFrameGrabber frameGrabber;

    /**
     * The videoplayer this thread attaches to & provides with the current image to show.
     */
    private final VideoPlayer parent;
    /**
     * Indicates whether the current thread is paused.
     */
    private boolean paused;

    /**
     * Default constructor.
     *
     * @param parent the video player to attach to
     */
    public VideoThread(final VideoPlayer parent) {
        this.parent = parent;
    }

    /**
     * Overwrites the thread's start method, starts the famegrabber
     */
    @Override
    public void start() {
        this.frameGrabber = new FFmpegFrameGrabber(CLIArgs.getInput()) {
            // We have to overwrite this method for the grabbing to work correctly.
            @Override
            public Frame grabAtFrameRate() throws FrameGrabber.Exception, InterruptedException {
                final Frame frame = grabImage();
                if (frame != null) {
                    waitForTimestamp(frame);
                }
                return frame;
            }
        };
        FFmpegUtil.configureDecoder(frameGrabber);
        frameGrabber.setVideoCodecName("hevc_cuvid");

        try {
            frameGrabber.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new RuntimeException(e);
        }
        super.start();
    }

    @Override
    public void run() {
        super.run();
        try {
            /* Grab frames until there are no more or the thread has been interrupted */
            Frame frame;
            while ((frame = frameGrabber.grabAtFrameRate()) != null && !Thread.interrupted()) {
                if (frame.image == null)
                    continue;

                // Convert the frame to an opencv.core Mat
                final org.opencv.core.Mat coreFrameMat = FRAME_CONVERTER.convertToOrgOpenCvCoreMat(frame);

                // Render the string s to the created mat
                final String s = "Segments: " + Editor.INSTANCE.stamps() + "; Required: " + Editor.INSTANCE.beats();
                Imgproc.putText(coreFrameMat, s, TEXT_ORIGIN, FONT_HERSHEY_PLAIN, 4, SCALAR_WHITE);

                // Show the image (convert the previously written to mat to a new frame)
                parent.showImage(FRAME_CONVERTER.convert(coreFrameMat));

                // Wait if the thread is paused, needs to sync the thread.
                synchronized (this) {
                    if (paused) {
                        wait();
                    }
                }
            } // End frame grabbing
            frameGrabber.close();
        } catch (FrameGrabber.Exception | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void pause() {
        // Unpause
        if (paused) {
            synchronized (this) {
                this.notify(); //Stop the wait
            }
        }
        this.paused = !paused;
    }

    /**
     * Seek some amount in the stream to a given timestamp
     *
     * @param amount the amount so seek in microseconds
     */
    public void seek(final long amount) {
        try {
            long prevTimeStamp = frameGrabber.getTimestamp();
            frameGrabber.setTimestamp(prevTimeStamp + amount);
            frameGrabber.resetStartTime();
        } catch (FFmpegFrameGrabber.Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public long getCurrentTimeStamp() {
        return frameGrabber.getTimestamp();
    }

    public void seekTo(long stamp) {
        try {
            frameGrabber.setTimestamp(stamp);
            frameGrabber.resetStartTime();
        } catch (FFmpegFrameGrabber.Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
