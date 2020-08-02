package net.earthcomputer.musicalcode.gradle;

import org.gradle.api.Project;

import java.io.File;

public class MusicalCodeExtension {
    private final Project project;
    private String from;
    private String to;
    private File config;
    private File output;

    public MusicalCodeExtension(Project project) {
        this.project = project;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public void from(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public void to(String to) {
        this.to = to;
    }

    public File getConfig() {
        return config;
    }

    public void setConfig(File config) {
        this.config = config;
    }

    public void config(Object config) {
        this.config = project.file(config);
    }

    public File getOutput() {
        return output;
    }

    public void setOutput(File output) {
        this.output = output;
    }

    public void output(Object output) {
        this.output = project.file(output);
    }
}
