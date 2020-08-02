package net.earthcomputer.musicalcode.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class MusicalCodeGradle implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().create("musicalCode", MusicalCodeExtension.class, project);
        project.getTasks().register("musicalCodeTask", MusicalCodeTask.class, task -> task.setDescription("Runs MusicalCode. Must specify either --from or --to on the command line or in the musicalCode extension."));
    }
}
