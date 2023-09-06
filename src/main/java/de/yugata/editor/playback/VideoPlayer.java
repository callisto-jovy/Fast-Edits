package de.yugata.editor.playback;

import org.bytedeco.javacv.CanvasFrame;

import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static java.awt.event.KeyEvent.*;

public class VideoPlayer extends CanvasFrame {

    private final VideoThread videoThread;

    private final List<Double> timeStamps = new ArrayList<>();
    private final Consumer<List<Double>> onEditingDone;

    public VideoPlayer(final String videoInput, Consumer<List<Double>> onEditingDone) {
        super("Easy Editor");
        this.videoThread = new VideoThread(videoInput, this);
        this.onEditingDone = onEditingDone;

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                videoThread.stopGrabber();
                System.out.println("Closed");
                e.getWindow().dispose();
            }
        });
    }

    public void run() {
        videoThread.start();
        // key event thread
        new Thread(() -> {
            try {
                while (true) {
                    final KeyEvent key = waitKey();

                    final long skipSeconds_10 = 10 * 1000000L;
                    final long skipSeconds_60 = 6 * skipSeconds_10;

                    switch (key.getKeyCode()) {

                        case VK_SPACE:
                            videoThread.pause();
                            break;

                        case VK_LEFT:
                            videoThread.seek(-skipSeconds_10);
                            break;

                        case VK_RIGHT:
                            videoThread.seek(skipSeconds_10);
                            break;

                        case VK_UP:
                            videoThread.seek(skipSeconds_60);
                            break;

                        case VK_DOWN:
                            videoThread.seek(-skipSeconds_60);
                            break;

                        // Ending the editing
                        case VK_ESCAPE:
                            videoThread.interrupt();
                            this.dispose();
                            onEditingDone.accept(timeStamps);
                            break;

                        case VK_X:
                            timeStamps.add(videoThread.getCurrentTimeStamp());
                            getGraphics().drawString("" + timeStamps.size(), 10, 10);
                            System.out.println("Added new timestamp " + timeStamps.size());
                            break;

                    }
                }
            } catch (java.lang.Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
    }
}
