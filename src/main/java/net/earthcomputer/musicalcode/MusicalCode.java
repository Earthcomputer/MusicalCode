package net.earthcomputer.musicalcode;

import com.google.gson.Gson;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.fabricmc.stitch.commands.CommandMergeJar;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.jar.JarFile;

public class MusicalCode {

    private static final Gson GSON = new Gson();
    private static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String INTERMEDIARY_URL = "https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/%s.tiny";
    private static final String YARN_URL = "https://maven.fabricmc.net/net/fabricmc/yarn/%1$s/yarn-%1$s-v2.jar";

    public static void main(String... args) {
        OptionParser parser = new OptionParser();
        OptionSpec<Void> helpArg = parser.accepts("help", "Displays this help message").forHelp();
        OptionSpec<String> fromArg = parser.accepts("from", "The Minecraft version you're going from").withRequiredArg().required();
        OptionSpec<String> toArg = parser.accepts("to", "The Minecraft version you're going to").withRequiredArg().required();
        OptionSpec<File> configFile = parser.accepts("config", "The config file").withRequiredArg().ofType(File.class).defaultsTo(new File("config.txt"));
        OptionSpec<String> yarnArg = parser.accepts("yarn", "The yarn version to use for named mappings").withRequiredArg();
        OptionSpec<File> outputArg = parser.accepts("output", "The output file").withRequiredArg().ofType(File.class);
        OptionSpec<File> cacheDirArg = parser.accepts("cacheDir", "The cache directory").withRequiredArg().ofType(File.class).defaultsTo(new File("cache"));
        OptionSet options = parser.parse(args);
        if (options.has(helpArg)) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }

        File cacheDir = options.valueOf(cacheDirArg);
        String fromVersion = options.valueOf(fromArg);
        String toVersion = options.valueOf(toArg);
        if (fromVersion.equals(toVersion)) {
            throw new RuntimeException("fromVersion == toVersion");
        }
        File fromJar = downloadAndRemap(cacheDir, fromVersion);
        File toJar = downloadAndRemap(cacheDir, toVersion);

        TinyRemapper[] remappersToClose;
        Remapper intermediaryToYarnRemapper;
        Remapper yarnToIntermediaryRemapper;
        if (options.has(yarnArg)) {
            String yarnVersion = options.valueOf(yarnArg);
            File yarnJar = download(cacheDir, String.format(YARN_URL, yarnVersion), "yarn-" + yarnVersion + ".jar");
            TinyRemapper i2y = getYarnRemapper(fromJar, yarnJar, "intermediary", "named");
            TinyRemapper y2i = getYarnRemapper(fromJar, yarnJar, "named", "intermediary");
            remappersToClose = new TinyRemapper[] {i2y, y2i};
            intermediaryToYarnRemapper = i2y.getRemapper();
            yarnToIntermediaryRemapper = y2i.getRemapper();
        } else {
            remappersToClose = new TinyRemapper[0];
            intermediaryToYarnRemapper = yarnToIntermediaryRemapper = new Remapper() {};
        }

        MemberPattern memberPattern = MemberPattern.parse(options.valueOf(configFile), yarnToIntermediaryRemapper);

        Consumer<String> output;
        PrintWriter outputWriter;
        if (options.has(outputArg)) {
            try {
                outputWriter = new PrintWriter(new FileWriter(options.valueOf(outputArg)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            output = outputWriter::println;
        } else {
            outputWriter = null;
            output = System.out::println;
        }

        System.out.println("Comparing jars...");
        System.out.println("====================================");
        try (JarFile fromJarFile = new JarFile(fromJar); JarFile toJarFile = new JarFile(toJar)) {
            JarComparer.compare(fromJarFile, toJarFile, memberPattern, intermediaryToYarnRemapper, output, System.err::println);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        System.out.println("Finished comparison");
        for (TinyRemapper remapper : remappersToClose) {
            remapper.finish();
        }

        if (outputWriter != null) {
            outputWriter.flush();
            outputWriter.close();
        }

    }

    private static File downloadAndRemap(File cacheDir, String version) {
        File unmapped = downloadMcJar(cacheDir, version);
        return remapMcJar(cacheDir, version, unmapped);
    }

    private static File downloadMcJar(File cacheDir, String version) {
        File versionManifestFile = download(cacheDir, VERSION_MANIFEST, "version_manifest.json");
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

        File versionFile = download(cacheDir, versionUrl, version + ".json");
        Version v;
        try {
            v = GSON.fromJson(new FileReader(versionFile), Version.class);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }

        File clientJar = download(cacheDir, v.downloads.client.url, version + "-client.jar");
        File serverJar = download(cacheDir, v.downloads.server.url, version + "-server.jar");
        File mergedJar = new File(cacheDir, version + "-merged.jar");

        System.out.println("Merging " + version + " jars...");
        try {
            new CommandMergeJar().run(new String[] {clientJar.getAbsolutePath(), serverJar.getAbsolutePath(), mergedJar.getAbsolutePath()});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return mergedJar;
    }

    private static File remapMcJar(File cacheDir, String version, File input) {
        File intermediaryMappings = download(cacheDir, String.format(INTERMEDIARY_URL, version), version + "-intermediary.tiny");
        System.out.println("Remapping " + version + " to intermediary...");
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(intermediaryMappings.toPath(), "official", "intermediary"))
                .build();
        File output = new File(cacheDir, version + "-intermediary.jar");
        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output.toPath()).build()) {
            remapper.readInputs(input.toPath());
            remapper.apply(outputConsumer);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remap " + version, e);
        } finally {
            remapper.finish();
        }

        return output;
    }

    private static TinyRemapper getYarnRemapper(File fromJar, File yarnJar, String fromNamespace, String toNamespace) {
        System.out.println("Building yarn " + fromNamespace + " to " + toNamespace + " remapper...");
        try (JarFile yarnJarFile = new JarFile(yarnJar)) {
            BufferedReader tinyReader = new BufferedReader(new InputStreamReader(yarnJarFile.getInputStream(yarnJarFile.getEntry("mappings/mappings.tiny")), StandardCharsets.UTF_8));
            TinyRemapper remapper = TinyRemapper.newRemapper()
                    .withMappings(TinyUtils.createTinyMappingProvider(tinyReader, fromNamespace, toNamespace))
                    .build();
            remapper.readInputs(fromJar.toPath());
            remapper.getRemapper(); // force read the mappings
            return remapper;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static File download(File cacheDir, String urlString, String dest) {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Downloading " + dest + "...");

        File destFile = new File(cacheDir, dest);
        File etagFile = new File(cacheDir, dest + ".etag");

        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            if (destFile.exists() && etagFile.exists()) {
                String etag = com.google.common.io.Files.asCharSource(etagFile, StandardCharsets.UTF_8).read();
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
                com.google.common.io.Files.asCharSink(etagFile, StandardCharsets.UTF_8).write(etag);
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
