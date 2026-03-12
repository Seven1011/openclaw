package ai.openclaw.common.utils;

import java.util.Set;

public class MessageChannelUtils {

    public static final String INTERNAL_MESSAGE_CHANNEL = "webchat";

    private static final Set<String> MARKDOWN_CAPABLE_CHANNELS = Set.of(
            "slack",
            "telegram",
            "signal",
            "discord",
            "googlechat",
            "tui",
            INTERNAL_MESSAGE_CHANNEL
    );

    public static boolean isInternalMessageChannel(String raw) {
        return INTERNAL_MESSAGE_CHANNEL.equals(normalizeMessageChannel(raw));
    }

    public static String normalizeMessageChannel(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }
        // For now, we just return the normalized string.
        // In the future, this would check against a registry.
        return normalized;
    }

    public static boolean isMarkdownCapableMessageChannel(String raw) {
        String channel = normalizeMessageChannel(raw);
        return channel != null && MARKDOWN_CAPABLE_CHANNELS.contains(channel);
    }
}
