package net.earthcomputer.musicalcode.gradle;

import net.earthcomputer.musicalcode.MusicalCode;
import net.fabricmc.loom.LoomGradleExtension;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MusicalCodeTask extends DefaultTask {
    private String fromVersion;
    private String toVersion;
    private File config;
    private File output;

    public MusicalCodeTask() {
        // never cache this task
        getOutputs().upToDateWhen(task -> false);

        MusicalCodeExtension musicalCode = getProject().getExtensions().findByType(MusicalCodeExtension.class);
        assert musicalCode != null;
        fromVersion = musicalCode.getFrom();
        toVersion = musicalCode.getTo();
        config = musicalCode.getConfig();
        if (config == null) {
            config = getProject().file("config.txt");
        }
        output = musicalCode.getOutput();
    }

    @Option(option = "from", description = "The version to check from")
    public void from(String version) {
        this.fromVersion = version;
    }

    @Option(option = "to", description = "The version to check to")
    public void to(String version) {
        this.toVersion = version;
    }

    public void config(Object config) {
        this.config = getProject().file(config);
    }

    public void output(Object output) {
        this.output = getProject().file(output);
    }

    @TaskAction
    public void runTask() {
        LoomGradleExtension loom = getProject().getExtensions().findByType(LoomGradleExtension.class);
        if (loom == null) {
            throw new IllegalStateException("Missing loom");
        }
        if (fromVersion == null) {
            fromVersion = loom.getMinecraftProvider().getMinecraftVersion();
        }
        if (toVersion == null) {
            toVersion = loom.getMinecraftProvider().getMinecraftVersion();
        }

        if (fromVersion.equals(toVersion)) {
            throw new IllegalStateException("fromVersion == toVersion");
        }

        String yarnVersion = getProject().getConfigurations().getByName("mappings").getDependencies().stream()
                .filter(dep -> "net.fabricmc".equals(dep.getGroup()) && "yarn".equals(dep.getName()))
                .findAny()
                .map(Dependency::getVersion)
                .orElseThrow(() -> new IllegalStateException("Could not find yarn version"));

        getProject().getLogger().info("===== RUNNING MUSICAL CODE =====");
        List<String> args = new ArrayList<>();
        Collections.addAll(
                args, "--from", fromVersion,
                "--to", toVersion,
                "--config", config.getAbsolutePath(),
                "--yarn", yarnVersion,
                "--cacheDir", new File(loom.getUserCache(), "musical-code").getAbsolutePath()
        );
        if (output != null) {
            Collections.addAll(args, "--output", output.getAbsolutePath());
        }
        MusicalCode.main(args.toArray(new String[0]));
        getProject().getLogger().info("===== FINISHED RUNNING MUSICAL CODE =====");
    }
}
