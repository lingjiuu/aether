package io.github.lingjiuu.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.infra.config.AetherPaths;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ToolBinaryResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);

    private final Path toolsDir;
    private final CommandProbe commandProbe;
    private final ToolDownloader downloader;
    private final BooleanSupplier offlineMode;

    public static ToolBinaryResolver defaults() {
        return new ToolBinaryResolver(
                AetherPaths.getToolsDir(),
                ToolBinaryResolver::commandExists,
                new GithubReleaseToolDownloader(),
                ToolBinaryResolver::isOfflineModeEnabled
        );
    }

    public ToolBinaryResolver() {
        this(
                AetherPaths.getToolsDir(),
                ToolBinaryResolver::commandExists,
                new GithubReleaseToolDownloader(),
                ToolBinaryResolver::isOfflineModeEnabled
        );
    }

    ToolBinaryResolver(
            Path toolsDir,
            CommandProbe commandProbe,
            ToolDownloader downloader,
            BooleanSupplier offlineMode
    ) {
        if (toolsDir == null) {
            throw new IllegalArgumentException("toolsDir must not be null");
        }
        if (commandProbe == null) {
            throw new IllegalArgumentException("commandProbe must not be null");
        }
        if (downloader == null) {
            throw new IllegalArgumentException("downloader must not be null");
        }
        if (offlineMode == null) {
            throw new IllegalArgumentException("offlineMode must not be null");
        }
        this.toolsDir = toolsDir.toAbsolutePath().normalize();
        this.commandProbe = commandProbe;
        this.downloader = downloader;
        this.offlineMode = offlineMode;
    }

    public Optional<String> resolve(ToolBinary binary) {
        if (binary == null) {
            return Optional.empty();
        }

        Path managedBinary = toolsDir.resolve(binary.executableName(System.getProperty("os.name")));
        if (Files.isRegularFile(managedBinary)) {
            return Optional.of(managedBinary.toString());
        }

        for (String candidate : binary.systemBinaryNames()) {
            if (commandProbe.exists(candidate)) {
                return Optional.of(candidate);
            }
        }

        if (offlineMode.getAsBoolean()) {
            return Optional.empty();
        }

        try {
            Optional<Path> downloaded = downloader.download(binary, toolsDir);
            return downloaded.map(path -> path.toAbsolutePath().normalize().toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    interface CommandProbe {
        boolean exists(String command);
    }

    interface ToolDownloader {
        Optional<Path> download(ToolBinary binary, Path toolsDir);
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isOfflineModeEnabled() {
        String value = System.getenv("AETHER_OFFLINE");
        return value != null && (value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes"));
    }

    private static final class GithubReleaseToolDownloader implements ToolDownloader {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(NETWORK_TIMEOUT)
                .build();

        @Override
        public Optional<Path> download(ToolBinary binary, Path toolsDir) {
            try {
                Files.createDirectories(toolsDir);
                String version = latestVersion(binary);
                String assetName = binary.assetName(version, System.getProperty("os.name"), System.getProperty("os.arch"));
                if (assetName == null || assetName.isBlank()) {
                    return Optional.empty();
                }

                String url = "https://github.com/" + binary.githubRepo()
                        + "/releases/download/" + binary.releaseTagPrefix() + version
                        + "/" + assetName;
                Path archive = toolsDir.resolve(assetName);
                Path extractDir = toolsDir.resolve("extract_tmp_" + binary.binaryName() + "_" + UUID.randomUUID());
                Path finalBinary = toolsDir.resolve(binary.executableName(System.getProperty("os.name")));
                try {
                    downloadFile(url, archive);
                    Files.createDirectories(extractDir);
                    extractArchive(archive, extractDir, assetName);
                    Path extractedBinary = findBinary(extractDir, binary.executableName(System.getProperty("os.name")))
                            .orElseThrow(() -> new IllegalStateException("Binary not found in archive: " + binary.binaryName()));
                    Files.move(extractedBinary, finalBinary, StandardCopyOption.REPLACE_EXISTING);
                    if (!isWindows()) {
                        finalBinary.toFile().setExecutable(true, false);
                    }
                    return Optional.of(finalBinary);
                } finally {
                    deleteIfExists(archive);
                    deleteTree(extractDir);
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        private String latestVersion(ToolBinary binary) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + binary.githubRepo() + "/releases/latest"))
                    .timeout(NETWORK_TIMEOUT)
                    .header("User-Agent", "aether")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GitHub API error: " + response.statusCode());
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            String tag = root.path("tag_name").asText();
            if (tag.isBlank()) {
                throw new IOException("GitHub release has no tag_name");
            }
            return tag.startsWith("v") ? tag.substring(1) : tag;
        }

        private void downloadFile(String url, Path destination) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .header("User-Agent", "aether")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Failed to download: " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void extractArchive(Path archive, Path extractDir, String assetName) throws IOException, InterruptedException {
            if (assetName.endsWith(".tar.gz")) {
                Process process = new ProcessBuilder("tar", "xzf", archive.toString(), "-C", extractDir.toString())
                        .redirectErrorStream(true)
                        .start();
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IOException("Failed to extract " + assetName + ": tar exited with code " + exit);
                }
                return;
            }
            if (assetName.endsWith(".zip")) {
                extractZip(archive, extractDir);
                return;
            }
            throw new IOException("Unsupported archive format: " + assetName);
        }

        private void extractZip(Path archive, Path extractDir) throws IOException {
            try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zipInput.getNextEntry()) != null) {
                    Path output = extractDir.resolve(entry.getName()).normalize();
                    if (!output.startsWith(extractDir)) {
                        throw new IOException("Zip entry escapes extraction directory: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        Files.copy(zipInput, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zipInput.closeEntry();
                }
            }
        }

        private Optional<Path> findBinary(Path root, String binaryName) throws IOException {
            ArrayDeque<Path> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Path current = stack.pop();
                try (var stream = Files.list(current)) {
                    for (Path entry : stream.toList()) {
                        if (Files.isRegularFile(entry) && entry.getFileName().toString().equals(binaryName)) {
                            return Optional.of(entry);
                        }
                        if (Files.isDirectory(entry)) {
                            stack.push(entry);
                        }
                    }
                }
            }
            return Optional.empty();
        }

        private void deleteIfExists(Path path) throws IOException {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        }

        private void deleteTree(Path root) throws IOException {
            if (root == null || !Files.exists(root)) {
                return;
            }
            AtomicReference<IOException> failure = new AtomicReference<>();
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }
            if (failure.get() != null) {
                throw failure.get();
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        }
    }
}
