import de.yugata.easy.edits.wrapper.FlutterWrapper;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ExportFrameTest {


    public static void main(String[] args) {
        FlutterWrapper.initFrameExport("D:\\Edits\\Movies\\All Quiet On The Western Front\\All.Quiet.on.the.Western.Front.2022.2160p.UHD.BluRay.x265.HDR.DV.DD+7.1-Pahe.in.mkv", "");
        final ByteBuffer ints = FlutterWrapper.getFrame(50000000);
        System.out.println(ints.capacity());
        FlutterWrapper.stopFrameExport();
    }
}
