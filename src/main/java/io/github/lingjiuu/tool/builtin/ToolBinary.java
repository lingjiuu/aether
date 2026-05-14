package io.github.lingjiuu.tool.builtin;

import java.util.List;

public enum ToolBinary {
    FD(
            "fd",
            "fd",
            List.of("fd", "fdfind"),
            "sharkdp/fd",
            "v"
    ),
    RG(
            "ripgrep",
            "rg",
            List.of("rg"),
            "BurntSushi/ripgrep",
            ""
    );

    private final String displayName;
    private final String binaryName;
    private final List<String> systemBinaryNames;
    private final String githubRepo;
    private final String releaseTagPrefix;

    ToolBinary(
            String displayName,
            String binaryName,
            List<String> systemBinaryNames,
            String githubRepo,
            String releaseTagPrefix
    ) {
        this.displayName = displayName;
        this.binaryName = binaryName;
        this.systemBinaryNames = List.copyOf(systemBinaryNames);
        this.githubRepo = githubRepo;
        this.releaseTagPrefix = releaseTagPrefix;
    }

    public String displayName() {
        return displayName;
    }

    public String binaryName() {
        return binaryName;
    }

    public List<String> systemBinaryNames() {
        return systemBinaryNames;
    }

    public String githubRepo() {
        return githubRepo;
    }

    public String releaseTagPrefix() {
        return releaseTagPrefix;
    }

    public String assetName(String version, String os, String arch) {
        String platform = normalizeOs(os);
        String architecture = normalizeArch(arch);
        return switch (this) {
            case FD -> fdAssetName(version, platform, architecture);
            case RG -> rgAssetName(version, platform, architecture);
        };
    }

    public String executableName(String os) {
        return normalizeOs(os).equals("windows") ? binaryName + ".exe" : binaryName;
    }

    private String fdAssetName(String version, String platform, String architecture) {
        String archName = architecture.equals("aarch64") ? "aarch64" : "x86_64";
        return switch (platform) {
            case "mac" -> "fd-v" + version + "-" + archName + "-apple-darwin.tar.gz";
            case "linux" -> "fd-v" + version + "-" + archName + "-unknown-linux-gnu.tar.gz";
            case "windows" -> "fd-v" + version + "-" + archName + "-pc-windows-msvc.zip";
            default -> null;
        };
    }

    private String rgAssetName(String version, String platform, String architecture) {
        return switch (platform) {
            case "mac" -> "ripgrep-" + version + "-" + architecture + "-apple-darwin.tar.gz";
            case "linux" -> architecture.equals("aarch64")
                    ? "ripgrep-" + version + "-aarch64-unknown-linux-gnu.tar.gz"
                    : "ripgrep-" + version + "-x86_64-unknown-linux-musl.tar.gz";
            case "windows" -> "ripgrep-" + version + "-" + architecture + "-pc-windows-msvc.zip";
            default -> null;
        };
    }

    private static String normalizeOs(String os) {
        String value = os == null ? "" : os.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("mac") || value.contains("darwin")) {
            return "mac";
        }
        if (value.contains("win")) {
            return "windows";
        }
        if (value.contains("linux")) {
            return "linux";
        }
        return value;
    }

    private static String normalizeArch(String arch) {
        String value = arch == null ? "" : arch.toLowerCase(java.util.Locale.ROOT);
        if (value.equals("aarch64") || value.equals("arm64")) {
            return "aarch64";
        }
        return "x86_64";
    }
}
