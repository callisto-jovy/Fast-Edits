package de.yugata.easy.edits.editor.video;

import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

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
            final String identifier = new File(source).getName() + timeStamp + ".jpeg";

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

            final BufferedImage bufferedImage = Java2DFrameUtils.toBufferedImage(frame);

            final ByteArrayOutputStream compressed = new ByteArrayOutputStream();

            try (final ImageOutputStream outputStream = ImageIO.createImageOutputStream(compressed)) {

                // NOTE: The rest of the code is just a cleaned up version of your code

                // Obtain writer for JPEG format
                final ImageWriter jpgWriter = ImageIO.getImageWritersByFormatName("JPEG").next();

                // Configure JPEG compression: 30% quality
                final ImageWriteParam jpgWriteParam = jpgWriter.getDefaultWriteParam();
                jpgWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                jpgWriteParam.setCompressionQuality(0.3f);

                // Set your in-memory stream as the output
                jpgWriter.setOutput(outputStream);

                // Write image as JPEG w/configured settings to the in-memory stream
                // (the IIOImage is just an aggregator object, allowing you to associate
                // thumbnails and metadata to the image, it "does" nothing)
                jpgWriter.write(null, new IIOImage(bufferedImage, null, null), jpgWriteParam);

                // Dispose the writer to free resources
                jpgWriter.dispose();
            }

            // Write to file
            try (final FileOutputStream fileOutputStream = new FileOutputStream(output)) {
                compressed.writeTo(fileOutputStream);
            }

            return ByteBuffer.wrap(compressed.toByteArray());
        } catch (FFmpegFrameGrabber.Exception e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
