package de.yugata.editor.commands;


import de.yugata.editor.editor.Editor;
import de.yugata.editor.model.CLIArgs;
import org.springframework.shell.Availability;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellMethodAvailability;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class EditCommand {

    @ShellMethod(value = "Start the editing process", group = "Workflow")
    @ShellMethodAvailability("editingAvailability")
    public void edit(@ShellOption(value = {"use", "segments"}, defaultValue = "false") boolean useSegments) {
        Editor.INSTANCE.runEditing(useSegments);
    }

    public Availability editingAvailability() {
        return Editor.INSTANCE.editingPossible() ? Availability.available() : Availability.unavailable(CLIArgs.checkArguments());
    }
}
