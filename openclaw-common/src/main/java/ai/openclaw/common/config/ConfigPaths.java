package ai.openclaw.common.config;

import ai.openclaw.common.infra.HomeDirUtils;
import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigPaths {

    public static final String NEW_STATE_DIRNAME = ".openclaw";
    public static final String CONFIG_FILENAME = "openclaw.json";
    public static final List<String> LEGACY_STATE_DIRNAMES = List.of(".clawdbot", ".moldbot", ".moltbot");
    public static final List<String> LEGACY_CONFIG_FILENAMES = List.of("clawdbot.json", "moldbot.json", "moltbot.json");
    public static final int DEFAULT_GATEWAY_PORT = 18789;

    public static boolean isNixMode() {
        return "1".equals(System.getenv("OPENCLAW_NIX_MODE"));
    }

    public static String resolveStateDir() {
        String override = System.getenv("OPENCLAW_STATE_DIR");
        if (override == null || override.isBlank()) {
            override = System.getenv("CLAWDBOT_STATE_DIR");
        }
        if (override != null && !override.isBlank()) {
            return resolveUserPath(override);
        }

        if ("1".equals(System.getenv("OPENCLAW_TEST_FAST"))) {
            return newStateDir();
        }

        String newDir = newStateDir();
        if (new File(newDir).exists()) {
            return newDir;
        }

        for (String legacyDir : legacyStateDirs()) {
            if (new File(legacyDir).exists()) {
                return legacyDir;
            }
        }

        return newDir;
    }

    public static String resolveConfigPath() {
        String override = System.getenv("OPENCLAW_CONFIG_PATH");
        if (override == null || override.isBlank()) {
            override = System.getenv("CLAWDBOT_CONFIG_PATH");
        }
        if (override != null && !override.isBlank()) {
            return resolveUserPath(override);
        }

        if ("1".equals(System.getenv("OPENCLAW_TEST_FAST"))) {
            return Paths.get(resolveStateDir(), CONFIG_FILENAME).toString();
        }

        List<String> candidates = resolveDefaultConfigCandidates();
        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }

        return Paths.get(resolveStateDir(), CONFIG_FILENAME).toString();
    }

    public static List<String> resolveDefaultConfigCandidates() {
        String explicit = System.getenv("OPENCLAW_CONFIG_PATH");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("CLAWDBOT_CONFIG_PATH");
        }
        if (explicit != null && !explicit.isBlank()) {
            return List.of(resolveUserPath(explicit));
        }

        List<String> candidates = new ArrayList<>();
        String stateDirOverride = System.getenv("OPENCLAW_STATE_DIR");
        if (stateDirOverride == null || stateDirOverride.isBlank()) {
            stateDirOverride = System.getenv("CLAWDBOT_STATE_DIR");
        }
        
        if (stateDirOverride != null && !stateDirOverride.isBlank()) {
            String resolved = resolveUserPath(stateDirOverride);
            candidates.add(Paths.get(resolved, CONFIG_FILENAME).toString());
            for (String legacy : LEGACY_CONFIG_FILENAMES) {
                candidates.add(Paths.get(resolved, legacy).toString());
            }
        }

        List<String> defaultDirs = new ArrayList<>();
        defaultDirs.add(newStateDir());
        defaultDirs.addAll(legacyStateDirs());

        for (String dir : defaultDirs) {
            candidates.add(Paths.get(dir, CONFIG_FILENAME).toString());
            for (String legacy : LEGACY_CONFIG_FILENAMES) {
                candidates.add(Paths.get(dir, legacy).toString());
            }
        }
        return candidates;
    }

    public static String resolveGatewayLockDir() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        // Simplified UID handling for Java (we don't have easy access to getuid without JNA/process)
        // We'll use a property if available or just 'openclaw'
        String user = System.getProperty("user.name");
        String suffix = (user != null) ? "openclaw-" + user : "openclaw";
        return Paths.get(tmpDir, suffix).toString();
    }

    public static String resolveOAuthDir() {
        String override = System.getenv("OPENCLAW_OAUTH_DIR");
        if (override != null && !override.isBlank()) {
            return resolveUserPath(override);
        }
        return Paths.get(resolveStateDir(), "credentials").toString();
    }

    public static String resolveOAuthPath() {
        return Paths.get(resolveOAuthDir(), "oauth.json").toString();
    }

    private static String newStateDir() {
        return Paths.get(HomeDirUtils.resolveRequiredHomeDir(), NEW_STATE_DIRNAME).toString();
    }

    private static List<String> legacyStateDirs() {
        List<String> dirs = new ArrayList<>();
        String home = HomeDirUtils.resolveRequiredHomeDir();
        for (String name : LEGACY_STATE_DIRNAMES) {
            dirs.add(Paths.get(home, name).toString());
        }
        return dirs;
    }

    private static String resolveUserPath(String path) {
        String expanded = HomeDirUtils.expandHomePrefix(path);
        return Paths.get(expanded).toAbsolutePath().toString();
    }
}

