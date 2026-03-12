package ai.openclaw.common.infra;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class HomeDirUtils {

    public static String resolveEffectiveHomeDir() {
        return resolveRawHomeDir().map(p -> Paths.get(p).toAbsolutePath().toString()).orElse(null);
    }

    private static Optional<String> resolveRawHomeDir() {
        String explicitHome = normalize(System.getenv("OPENCLAW_HOME"));
        if (explicitHome != null) {
            if (explicitHome.equals("~") || explicitHome.startsWith("~/") || explicitHome.startsWith("~\\")) {
                String fallbackHome = getFallbackHome();
                if (fallbackHome != null) {
                    return Optional.of(explicitHome.replaceFirst("^~", fallbackHome));
                }
                return Optional.empty();
            }
            return Optional.of(explicitHome);
        }

        String envHome = normalize(System.getenv("HOME"));
        if (envHome != null) {
            return Optional.of(envHome);
        }

        String userProfile = normalize(System.getenv("USERPROFILE"));
        if (userProfile != null) {
            return Optional.of(userProfile);
        }

        return Optional.ofNullable(normalize(System.getProperty("user.home")));
    }

    private static String getFallbackHome() {
        String home = normalize(System.getenv("HOME"));
        if (home != null) return home;
        String userProfile = normalize(System.getenv("USERPROFILE"));
        if (userProfile != null) return userProfile;
        return normalize(System.getProperty("user.home"));
    }

    public static String resolveRequiredHomeDir() {
        String home = resolveEffectiveHomeDir();
        if (home != null) return home;
        return Paths.get("").toAbsolutePath().toString();
    }

    public static String expandHomePrefix(String input) {
        if (input == null || !input.startsWith("~")) {
            return input;
        }
        String home = resolveEffectiveHomeDir();
        if (home == null) {
            return input;
        }
        return input.replaceFirst("^~", home);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
