package de.yugata.easy.edits.wrapper;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.yugata.easy.edits.editor.edit.EditingFlag;
import de.yugata.easy.edits.editor.preview.PreviewEditor;
import de.yugata.easy.edits.editor.video.*;
import de.yugata.easy.edits.filter.FilterType;
import de.yugata.easy.edits.filter.FilterValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static de.yugata.easy.edits.filter.FilterManager.FILTER_MANAGER;

public class FlutterWrapper {

    public static List<FlutterFilterWrapper> getFilters() {
        return FILTER_MANAGER.getAvailableFilters();
    }

    public static Map<String, String> getEditingFlags() {
        return Arrays.stream(EditingFlag.values()).collect(Collectors.groupingBy(Enum::name, Collectors.mapping(EditingFlag::getDescription, Collectors.joining())));
    }

    // Stashed instance
    private static FrameExporter frameExporter;

    public static void initFrameExport(final String source) {
        if (frameExporter == null) {
            frameExporter = new FrameExporter(source);
        }
    }

    public static void stopFrameExport() {
        if (frameExporter != null) {
            frameExporter.destroyGrabber();
            frameExporter = null;
        }
    }

    public static int[] getFrame(final long timeStamp) {
        return frameExporter.exportFrame(timeStamp);
    }

    public static void exportSegments(final String json) {
        final ClipExporter exporter = ClipExporter.fromJson(json);

        exporter.exportClips();
    }

    public static void edit(final String json) {
        final VideoEditor videoEditor = new VideoEditorBuilder()
                .fromJson(json);

        videoEditor.edit();
    }

    public static String previewSegment(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        final PreviewEditor previewEditor = PreviewEditor.fromJson(root);

        final VideoClip videoClip = new VideoClip(root.getAsJsonObject("clip"));
        return previewEditor.generatePreview(videoClip);
    }

    public static String editPreviews(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final String audioPath = root.get("source_audio").getAsString();


        final JsonArray previewArray = root.getAsJsonArray("previews");

        final List<String> previews = new ArrayList<>();
        for (final JsonElement jsonElement : previewArray) {
            previews.add(jsonElement.getAsString());
        }

        final PreviewEditor previewEditor = PreviewEditor.fromJson(root);
        return previewEditor.editPreviews(previews, audioPath);
    }


    /**
     * Data class to pass to the frontend containing the data the frontend needs to display the filter,
     * as well as the needed data to convey the filter to the backend.
     */
    public static class FlutterFilterWrapper {

        private final String name;

        private final String displayName;

        private final String description;

        private final String helpText;

        private final FilterType filterType;

        private final List<FilterValue> values;


        public FlutterFilterWrapper(String name, String displayName, String description, String helpText, FilterType filterType, List<FilterValue> values) {
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.helpText = helpText;
            this.filterType = filterType;
            this.values = values;
        }


        public String getHelpText() {
            return helpText;
        }

        public String getName() {
            return name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public FilterType getFilterType() {
            return filterType;
        }

        public List<FilterValue> getValues() {
            return values;
        }
    }

}
