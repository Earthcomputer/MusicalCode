package net.earthcomputer.musicalcode;

import com.google.gson.Gson;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.fabricmc.stitch.commands.CommandMergeJar;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarFile;

public class MusicalCode {

    private static final Gson GSON = new Gson();
    private static final String CACHE_DIR = "cache";
    private static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String INTERMEDIARY_URL = "https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/%s.tiny";

    public static void main(String[] args) {
        OptionParser parser = new OptionParser();
        OptionSpec<Void> helpArg = parser.accepts("help", "Displays this help message").forHelp();
        OptionSpec<String> fromArg = parser.accepts("from", "The Minecraft version you're going from").withRequiredArg().required();
        OptionSpec<String> toArg = parser.accepts("to", "The Minecraft version you're going to").withRequiredArg().required();
        OptionSpec<File> configFile = parser.accepts("config", "The config file").withRequiredArg().ofType(File.class).defaultsTo(new File("config.txt"));
        OptionSet options = parser.parse(args);
        if (options.has(helpArg)) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }

        String fromVersion = options.valueOf(fromArg);
        String toVersion = options.valueOf(toArg);
        if (fromVersion.equals(toVersion)) {
            throw new RuntimeException("fromVersion == toVersion");
        }
        File fromJar = downloadAndRemap(fromVersion);
        File toJar = downloadAndRemap(toVersion);

        MemberPattern memberPattern = MemberPattern.parse(options.valueOf(configFile));

        System.out.println("Comparing jars...");
        System.out.println("====================================");
        try (JarFile fromJarFile = new JarFile(fromJar); JarFile toJarFile = new JarFile(toJar)) {
            JarComparer.compare(fromJarFile, toJarFile, memberPattern, System.out::println, System.err::println);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        System.out.println("Finished comparison");
    }

    private static File downloadAndRemap(String version) {
        File unmapped = downloadMcJar(version);
        return remapMcJar(version, unmapped);
    }

    private static File downloadMcJar(String version) {
        File versionManifestFile = download(VERSION_MANIFEST, "version_manifest.json");
        VersionManifest versionManifest;
        try {
            versionManifest = GSON.fromJson(new FileReader(versionManifestFile), VersionManifest.class);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
        String versionUrl = null;
        for (VersionManifest.Version v : versionManifest.versions) {
            if (v.id.equals(version)) {
                versionUrl = v.url;
                break;
            }
        }
        if (versionUrl == null) {
            throw new RuntimeException("Unknown version: " + version);
        }

        File versionFile = download(versionUrl, version + ".json");
        Version v;
        try {
            v = GSON.fromJson(new FileReader(versionFile), Version.class);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }

        File clientJar = download(v.downloads.client.url, version + "-client.jar");
        File serverJar = download(v.downloads.server.url, version + "-server.jar");
        File mergedJar = new File(CACHE_DIR, version + "-merged.jar");

        System.out.println("Merging " + version + " jars...");
        try {
            new CommandMergeJar().run(new String[] {clientJar.getAbsolutePath(), serverJar.getAbsolutePath(), mergedJar.getAbsolutePath()});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return mergedJar;
    }

    private static File remapMcJar(String version, File input) {
        File intermediaryMappings = download(String.format(INTERMEDIARY_URL, version), version + "-intermediary.tiny");
        System.out.println("Remapping " + version + " to intermediary...");
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(intermediaryMappings.toPath(), "official", "intermediary"))
                .build();
        File output = new File(CACHE_DIR, version + "-intermediary.jar");
        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output.toPath()).build()) {
            remapper.readInputs(input.toPath());
            remapper.apply(outputConsumer);
            remapper.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remap " + version, e);
        }

        return output;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static File download(String urlString, String dest) {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Downloading " + dest + "...");

        File destFile = new File(CACHE_DIR, dest);
        File etagFile = new File(CACHE_DIR, dest + ".etag");

        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            if (destFile.exists() && etagFile.exists()) {
                String etag = Files.readString(etagFile.toPath(), StandardCharsets.UTF_8);
                connection.setRequestProperty("If-None-Match", etag);
            }

            connection.connect();

            int responseCode = connection.getResponseCode();
            if ((responseCode < 200 || responseCode > 299) && responseCode != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw new IOException("Got HTTP " + responseCode + " from " + url);
            }

            long lastModified = connection.getHeaderFieldDate("Last-Modified", -1);
            if (destFile.exists() && (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED || lastModified > 0 && destFile.lastModified() >= lastModified))
                return destFile;

            destFile.getParentFile().mkdirs();
            try {
                Files.copy(connection.getInputStream(), destFile.toPath());
            } catch (IOException e) {
                destFile.delete();
                throw e;
            }

            if (lastModified > 0)
                destFile.setLastModified(lastModified);

            String etag = connection.getHeaderField("ETag");
            if (etag != null) {
                Files.writeString(etagFile.toPath(), etag, StandardCharsets.UTF_8);
            }
            return destFile;
        } catch (UnknownHostException e) {
            if (destFile.exists()) {
                return destFile;
            }
            throw new UncheckedIOException("Error downloading file " + dest + " from " + url, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Error downloading file " + dest + " from " + url, e);
        }
    }

    private static class VersionManifest {
        private Version[] versions;
        private static class Version {
            private String id;
            private String url;
        }
    }

    private static class Version {
        private Downloads downloads;
        private static class Downloads {
            private Download client;
            private Download server;
            private static class Download {
                private String url;
            }
        }
    }

}
