package io.github.lingjiuu;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppVersion {

    private static final String VERSION_RESOURCE = "/aether-version.properties";

    private AppVersion() {
    }

    public static String current() {
        String resourceVersion = resourceVersion();
        if (isUsableVersion(resourceVersion)) {
            return resourceVersion;
        }

        String implementationVersion = AppVersion.class.getPackage().getImplementationVersion();
        if (isUsableVersion(implementationVersion)) {
            return implementationVersion;
        }

        return "dev";
    }

    private static String resourceVersion() {
        try (InputStream input = AppVersion.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version");
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isUsableVersion(String version) {
        return version != null
                && !version.isBlank()
                && !version.contains("${");
    }
}
