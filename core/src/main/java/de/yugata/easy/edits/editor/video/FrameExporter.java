package de.yugata.easy.edits.editor.video;

import de.yugata.easy.edits.util.FFmpegUtil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.nio.*;

public class FrameExporter {

    private final String source;

    /**
     * The {@see FrameGrabber} which grabs the input video
     */
    private FFmpegFrameGrabber videoGrabber;

    public FrameExporter(final String source) {
        this.source = source;
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

    public int[] exportFrame(final long timeStamp) {
        if (videoGrabber == null) {
            return null;
        }

        try {
            videoGrabber.setTimestamp(timeStamp);

            final Frame frame = videoGrabber.grabImage();

            if (frame == null) {
                return null;
            }

            final Buffer buffer = frame.image[0];
            ByteBuffer byteBuffer = ByteBuffer.allocate(buffer.capacity());


            if (buffer instanceof ShortBuffer) {
                final ShortBuffer shortBuffer = (ShortBuffer) buffer;
                byteBuffer.asShortBuffer().put(shortBuffer);
            } else if (buffer instanceof ByteBuffer) {
                byteBuffer = (ByteBuffer) buffer;
            } else if (buffer instanceof IntBuffer) {
                final IntBuffer intBuffer = (IntBuffer) buffer;
                byteBuffer.asIntBuffer().put(intBuffer);
            } else if (buffer instanceof LongBuffer) {
                final LongBuffer longBuffer = (LongBuffer) buffer;
                byteBuffer.asLongBuffer().put(longBuffer);
            } else if (buffer instanceof FloatBuffer) {
                final FloatBuffer floatBuffer = (FloatBuffer) buffer;
                byteBuffer.asFloatBuffer().put(floatBuffer);
            } else if (buffer instanceof DoubleBuffer) {
                final DoubleBuffer doubleBuffer = (DoubleBuffer) buffer;
                byteBuffer.asDoubleBuffer().put(doubleBuffer);
            }

            return byteBuffer.asIntBuffer().array();
        } catch (FFmpegFrameGrabber.Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
