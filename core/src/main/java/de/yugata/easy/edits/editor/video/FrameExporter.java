package de.yugata.easy.edits.editor.video;

import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class FrameExporter {

    private final String source, workingPath;

    /**
     * The {@see FrameGrabber} which grabs the input video
     */
    private FFmpegFrameGrabber videoGrabber;

    public FrameExporter(final String source, final String workingPath) {
        this.source = source;
        this.workingPath = workingPath;
        this.initFrameGrabber();
    }

    /**
     * Initializes & configure the frame grabber for the input video.
     */
    private void initFrameGrabber() {
        if (videoGrabber == null) {
            try {
                this.videoGrabber = new FFmpegFrameGrabber(source);
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

    public void destroyGrabber() {
        if (videoGrabber != null) {
            try {
                videoGrabber.close();
            } catch (FrameGrabber.Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public ByteBuffer exportFrame(final long timeStamp) {
        try {
            final String identifier = source + timeStamp + ".jpeg";

            final File workingDir = new File(workingPath);

            if (!workingDir.exists()) {
                workingDir.mkdirs();
            }

            final File output = new File(workingDir, identifier);

            if (output.exists()) {
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try (final FileInputStream fileInputStream = new FileInputStream(output)) {
                    fileInputStream.transferTo(byteArrayOutputStream);
                }

                return ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
            }

            videoGrabber.setTimestamp(timeStamp);

            final Frame frame = videoGrabber.grabImage();

            if (frame == null) {
                throw new RuntimeException("Frame is null");
            }

            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            final BufferedImage bufferedImage = Java2DFrameUtils.toBufferedImage(frame);
            ImageIO.write(bufferedImage, "JPEG", byteArrayOutputStream);

            return ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
        } catch (FFmpegFrameGrabber.Exception e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
