package ai.openclaw.agents.auth;

import ai.openclaw.common.config.OpenClawConfig;
import ai.openclaw.common.config.ConfigPaths;
import ai.openclaw.common.infra.HomeDirUtils;
import ai.openclaw.common.logging.StructuredLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages AI provider credentials, rotation, and failover.
 * Maps TypeScript: src/agents/auth-profiles/*.ts
 */
public class AuthProfileManager {

    private static final StructuredLogger logger = StructuredLogger.create("agents/auth-profiles");

    private static final int AUTH_STORE_VERSION = 1;
    private static final String AUTH_PROFILE_FILENAME = "auth-profiles.json";
    private static final String LEGACY_AUTH_FILENAME = "auth.json";

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock storeLock = new ReentrantReadWriteLock();
    private final Map<String, AuthProfileStore> runtimeSnapshots = new ConcurrentHashMap<>();

    private static final List<String> FAILURE_REASON_PRIORITY = List.of(
        "auth_permanent", "auth", "billing", "format", "model_not_found",
        "overloaded", "timeout", "rate_limit", "unknown"
    );

    private static final Set<String> BYPASS_COOLDOWN_PROVIDERS = Set.of("openrouter", "kilocode");

    public AuthProfileManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ==================== Records ====================

    public sealed interface AuthProfileCredential {
        String type();
        String provider();
    }

    public record ApiKeyCredential(
        String type,
        String provider,
        String key,
        SecretRef keyRef,
        String email,
        Map<String, String> metadata
    ) implements AuthProfileCredential {
        public ApiKeyCredential {
            type = "api_key";
        }
    }

    public record TokenCredential(
        String type,
        String provider,
        String token,
        SecretRef tokenRef,
        Long expires,
        String email
    ) implements AuthProfileCredential {
        public TokenCredential {
            type = "token";
        }
    }

    public record OAuthCredential(
        String type,
        String provider,
        String access,
        String refresh,
        Long expires,
        String clientId,
        String email,
        String enterpriseUrl,
        String projectId,
        String accountId
    ) implements AuthProfileCredential {
        public OAuthCredential {
            type = "oauth";
        }
    }

    public record SecretRef(
        String type,
        String location
    ) {}

    public record ProfileUsageStats(
        Long lastUsed,
        Long cooldownUntil,
        Long disabledUntil,
        String disabledReason,
        Integer errorCount,
        Map<String, Integer> failureCounts,
        Long lastFailureAt
    ) {
        public ProfileUsageStats {
            errorCount = errorCount != null ? errorCount : 0;
        }
    }

    public record AuthProfileStore(
        int version,
        Map<String, AuthProfileCredential> profiles,
        Map<String, List<String>> order,
        Map<String, String> lastGood,
        Map<String, ProfileUsageStats> usageStats
    ) {
        public AuthProfileStore {
            profiles = profiles != null ? new ConcurrentHashMap<>(profiles) : new ConcurrentHashMap<>();
            order = order != null ? new ConcurrentHashMap<>(order) : new ConcurrentHashMap<>();
            lastGood = lastGood != null ? new ConcurrentHashMap<>(lastGood) : new ConcurrentHashMap<>();
            usageStats = usageStats != null ? new ConcurrentHashMap<>(usageStats) : new ConcurrentHashMap<>();
        }
    }

    public record AuthProfileEligibility(
        boolean eligible,
        String reasonCode
    ) {}

    public record AuthFailureReason(
        String reason,
        long timestamp
    ) {}

    // ==================== Store Management ====================

    /**
     * Load the auth profile store.
     */
    public AuthProfileStore loadAuthProfileStore() {
        String authPath = resolveAuthStorePath();

        try {
            if (Files.exists(Path.of(authPath))) {
                AuthProfileStore store = objectMapper.readValue(Path.of(authPath).toFile(), AuthProfileStore.class);
                if (store != null) {
                    return store;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load auth store", Map.of("path", authPath, "error", e.getMessage()));
        }

        // Try legacy format
        String legacyPath = resolveLegacyAuthStorePath();
        try {
            if (Files.exists(Path.of(legacyPath))) {
                Map<String, Object> legacy = objectMapper.readValue(Path.of(legacyPath).toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                if (legacy != null && !legacy.containsKey("profiles")) {
                    AuthProfileStore migrated = migrateLegacyStore(legacy);
                    saveAuthProfileStore(migrated);
                    Files.deleteIfExists(Path.of(legacyPath));
                    return migrated;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load legacy auth store", Map.of("path", legacyPath));
        }

        return new AuthProfileStore(AUTH_STORE_VERSION, new HashMap<>(), null, null, null);
    }

    /**
     * Save the auth profile store.
     */
    public void saveAuthProfileStore(AuthProfileStore store) {
        String authPath = resolveAuthStorePath();

        try {
            Files.createDirectories(Path.of(authPath).getParent());

            // Sanitize credentials before saving
            Map<String, AuthProfileCredential> sanitizedProfiles = store.profiles().entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> sanitizeCredential(e.getValue()),
                    (a, b) -> a,
                    LinkedHashMap::new
                ));

            AuthProfileStore sanitizedStore = new AuthProfileStore(
                store.version(),
                sanitizedProfiles,
                store.order(),
                store.lastGood(),
                store.usageStats()
            );

            objectMapper.writeValue(Path.of(authPath).toFile(), sanitizedStore);

        } catch (IOException e) {
            logger.error("Failed to save auth store", Map.of("path", authPath, "error", e.getMessage()));
        }
    }

    private AuthProfileCredential sanitizeCredential(AuthProfileCredential cred) {
        if (cred instanceof ApiKeyCredential akc && akc.keyRef() != null && akc.key() != null) {
            return new ApiKeyCredential("api_key", akc.provider(), null, akc.keyRef(), akc.email(), akc.metadata());
        }
        if (cred instanceof TokenCredential tc && tc.tokenRef() != null && tc.token() != null) {
            return new TokenCredential("token", tc.provider(), null, tc.tokenRef(), tc.expires(), tc.email());
        }
        return cred;
    }

    private AuthProfileStore migrateLegacyStore(Map<String, Object> legacy) {
        Map<String, AuthProfileCredential> profiles = new HashMap<>();

        for (Map.Entry<String, Object> entry : legacy.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> credMap = (Map<String, Object>) entry.getValue();
                String type = (String) credMap.get("type");
                String provider = (String) credMap.getOrDefault("provider", entry.getKey());

                AuthProfileCredential cred = parseLegacyCredential(type, provider, credMap);
                if (cred != null) {
                    profiles.put(entry.getKey(), cred);
                }
            }
        }

        return new AuthProfileStore(AUTH_STORE_VERSION, profiles, null, null, null);
    }

    private AuthProfileCredential parseLegacyCredential(String type, String provider, Map<String, Object> map) {
        if ("api_key".equals(type)) {
            return new ApiKeyCredential("api_key", provider, (String) map.get("key"), null,
                (String) map.get("email"), null);
        }
        if ("token".equals(type)) {
            Number expires = (Number) map.get("expires");
            return new TokenCredential("token", provider, (String) map.get("token"), null,
                expires != null ? expires.longValue() : null, (String) map.get("email"));
        }
        if ("oauth".equals(type)) {
            Number expires = (Number) map.get("expires");
            return new OAuthCredential("oauth", provider, (String) map.get("access"),
                (String) map.get("refresh"), expires != null ? expires.longValue() : null,
                (String) map.get("clientId"), (String) map.get("email"),
                (String) map.get("enterpriseUrl"), (String) map.get("projectId"),
                (String) map.get("accountId"));
        }
        return null;
    }

    // ==================== Profile Management ====================

    /**
     * Upsert a credential profile.
     */
    public void upsertAuthProfile(String profileId, AuthProfileCredential credential) {
        storeLock.writeLock().lock();
        try {
            AuthProfileStore store = loadAuthProfileStore();
            store.profiles().put(profileId, normalizeCredential(credential));
            saveAuthProfileStore(store);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /**
     * Delete a profile.
     */
    public boolean deleteAuthProfile(String profileId) {
        storeLock.writeLock().lock();
        try {
            AuthProfileStore store = loadAuthProfileStore();
            boolean removed = store.profiles().remove(profileId) != null;
            if (removed) {
                saveAuthProfileStore(store);
            }
            return removed;
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /**
     * Set profile order for a provider.
     */
    public void setAuthProfileOrder(String provider, List<String> order) {
        storeLock.writeLock().lock();
        try {
            AuthProfileStore store = loadAuthProfileStore();
            if (order == null || order.isEmpty()) {
                store.order().remove(provider);
            } else {
                List<String> deduped = order.stream().distinct().collect(Collectors.toList());
                store.order().put(provider, deduped);
            }
            saveAuthProfileStore(store);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    private AuthProfileCredential normalizeCredential(AuthProfileCredential cred) {
        if (cred instanceof ApiKeyCredential akc && akc.key() != null) {
            return new ApiKeyCredential("api_key", akc.provider(), akc.key().trim(), akc.keyRef(),
                akc.email(), akc.metadata());
        }
        if (cred instanceof TokenCredential tc && tc.token() != null) {
            return new TokenCredential("token", tc.provider(), tc.token().trim(), tc.tokenRef(),
                tc.expires(), tc.email());
        }
        return cred;
    }

    // ==================== Profile Query ====================

    /**
     * List profiles for a provider.
     */
    public List<String> listProfilesForProvider(AuthProfileStore store, String provider) {
        String providerKey = normalizeProviderId(provider);
        return store.profiles().entrySet().stream()
            .filter(e -> normalizeProviderId(e.getValue().provider()).equals(providerKey))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Resolve auth profile order for a provider.
     */
    public List<String> resolveAuthProfileOrder(OpenClawConfig cfg, AuthProfileStore store, String provider) {
        return resolveAuthProfileOrder(cfg, store, provider, null);
    }

    /**
     * Resolve auth profile order for a provider with preferred profile.
     */
    public List<String> resolveAuthProfileOrder(OpenClawConfig cfg, AuthProfileStore store,
                                                 String provider, String preferredProfile) {
        String providerKey = normalizeProviderId(provider);
        long now = System.currentTimeMillis();

        // Clear expired cooldowns
        clearExpiredCooldowns(store, now);

        // Determine base order
        List<String> explicitOrder = store.order().get(providerKey);
        if (explicitOrder == null || explicitOrder.isEmpty()) {
            explicitOrder = listProfilesForProvider(store, provider);
        }

        if (explicitOrder.isEmpty()) {
            return List.of();
        }

        // Filter eligible profiles
        List<String> eligible = explicitOrder.stream()
            .filter(p -> resolveAuthProfileEligibility(cfg, store, provider, p, now).eligible())
            .distinct()
            .collect(Collectors.toList());

        // Sort by availability and preference
        List<String> available = new ArrayList<>();
        List<ProfileCooldown> inCooldown = new ArrayList<>();

        for (String profileId : eligible) {
            if (isProfileInCooldown(store, profileId, now)) {
                long cooldownUntil = resolveProfileUnusableUntil(store.usageStats().get(profileId));
                inCooldown.add(new ProfileCooldown(profileId, cooldownUntil != 0 ? cooldownUntil : now));
            } else {
                available.add(profileId);
            }
        }

        // Sort by credential type preference (oauth > token > api_key)
        List<String> sortedAvailable = available.stream()
            .sorted(Comparator.comparingInt((String p) -> getCredentialTypeScore(store, p))
                .thenComparing(p -> getLastUsed(store, p)))
            .collect(Collectors.toList());

        // Sort cooldown profiles by expiry
        List<String> sortedCooldown = inCooldown.stream()
            .sorted(Comparator.comparingLong(ProfileCooldown::cooldownUntil))
            .map(ProfileCooldown::profileId)
            .collect(Collectors.toList());

        List<String> result = new ArrayList<>(sortedAvailable);
        result.addAll(sortedCooldown);

        // Put preferred profile first if specified
        if (preferredProfile != null && result.contains(preferredProfile)) {
            result.remove(preferredProfile);
            result.add(0, preferredProfile);
        }

        return result;
    }

    private record ProfileCooldown(String profileId, long cooldownUntil) {}

    private int getCredentialTypeScore(AuthProfileStore store, String profileId) {
        AuthProfileCredential cred = store.profiles().get(profileId);
        if (cred == null) return 3;
        return switch (cred.type()) {
            case "oauth" -> 0;
            case "token" -> 1;
            case "api_key" -> 2;
            default -> 3;
        };
    }

    private long getLastUsed(AuthProfileStore store, String profileId) {
        ProfileUsageStats stats = store.usageStats().get(profileId);
        return stats != null && stats.lastUsed() != null ? stats.lastUsed() : 0;
    }

    /**
     * Check if a profile is eligible for use.
     */
    public AuthProfileEligibility resolveAuthProfileEligibility(OpenClawConfig cfg, AuthProfileStore store,
                                                                 String provider, String profileId, Long now) {
        long currentTime = now != null ? now : System.currentTimeMillis();

        AuthProfileCredential cred = store.profiles().get(profileId);
        if (cred == null) {
            return new AuthProfileEligibility(false, "profile_missing");
        }

        if (!normalizeProviderId(cred.provider()).equals(normalizeProviderId(provider))) {
            return new AuthProfileEligibility(false, "provider_mismatch");
        }

        if (isProfileInCooldown(store, profileId, currentTime)) {
            return new AuthProfileEligibility(false, "in_cooldown");
        }

        return new AuthProfileEligibility(true, "ok");
    }

    // ==================== Cooldown & Failure Management ====================

    /**
     * Check if a profile is in cooldown.
     */
    public boolean isProfileInCooldown(AuthProfileStore store, String profileId, Long now) {
        AuthProfileCredential cred = store.profiles().get(profileId);
        if (cred == null) return false;

        if (BYPASS_COOLDOWN_PROVIDERS.contains(normalizeProviderId(cred.provider()))) {
            return false;
        }

        ProfileUsageStats stats = store.usageStats().get(profileId);
        if (stats == null) return false;

        long ts = now != null ? now : System.currentTimeMillis();
        Long unusableUntil = resolveProfileUnusableUntil(stats);
        return unusableUntil != null && ts < unusableUntil;
    }

    /**
     * Mark a profile as successfully used.
     */
    public void markAuthProfileUsed(AuthProfileStore store, String profileId) {
        storeLock.writeLock().lock();
        try {
            ProfileUsageStats existing = store.usageStats().getOrDefault(profileId, new ProfileUsageStats(null, null, null, null, 0, null, null));
            ProfileUsageStats updated = new ProfileUsageStats(
                System.currentTimeMillis(),
                null,
                existing.disabledUntil(),
                existing.disabledReason(),
                0,
                null,
                existing.lastFailureAt()
            );
            store.usageStats().put(profileId, updated);
            saveAuthProfileStore(store);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /**
     * Mark a profile as failed.
     */
    public void markAuthProfileFailure(AuthProfileStore store, String profileId, String reason) {
        storeLock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            ProfileUsageStats existing = store.usageStats().getOrDefault(profileId, new ProfileUsageStats(null, null, null, null, 0, new HashMap<>(), null));

            int nextErrorCount = (existing.errorCount() != null ? existing.errorCount() : 0) + 1;
            Map<String, Integer> failureCounts = new HashMap<>(existing.failureCounts() != null ? existing.failureCounts() : new HashMap<>());
            failureCounts.merge(reason, 1, Integer::sum);

            Long cooldownUntil;
            Long disabledUntil;
            String disabledReason;

            if ("billing".equals(reason) || "auth_permanent".equals(reason)) {
                long backoffMs = calculateBillingBackoffMs(nextErrorCount);
                disabledUntil = keepActiveWindow(existing.disabledUntil(), now, now + backoffMs);
                disabledReason = reason;
                cooldownUntil = existing.cooldownUntil();
            } else {
                long backoffMs = calculateCooldownMs(nextErrorCount);
                cooldownUntil = keepActiveWindow(existing.cooldownUntil(), now, now + backoffMs);
                disabledUntil = existing.disabledUntil();
                disabledReason = existing.disabledReason();
            }

            ProfileUsageStats updated = new ProfileUsageStats(
                existing.lastUsed(),
                cooldownUntil,
                disabledUntil,
                disabledReason,
                nextErrorCount,
                failureCounts,
                now
            );

            store.usageStats().put(profileId, updated);
            saveAuthProfileStore(store);

            logger.info("Profile marked as failed",
                Map.of("profileId", profileId, "reason", reason, "errorCount", nextErrorCount));

        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /**
     * Clear cooldown for a profile.
     */
    public void clearAuthProfileCooldown(AuthProfileStore store, String profileId) {
        storeLock.writeLock().lock();
        try {
            ProfileUsageStats existing = store.usageStats().get(profileId);
            if (existing == null) return;

            ProfileUsageStats updated = new ProfileUsageStats(
                existing.lastUsed(),
                null,
                null,
                null,
                0,
                null,
                existing.lastFailureAt()
            );

            store.usageStats().put(profileId, updated);
            saveAuthProfileStore(store);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /**
     * Clear expired cooldowns from the store.
     */
    public boolean clearExpiredCooldowns(AuthProfileStore store, long now) {
        boolean mutated = false;

        for (Map.Entry<String, ProfileUsageStats> entry : store.usageStats().entrySet()) {
            ProfileUsageStats stats = entry.getValue();
            if (stats == null) continue;

            boolean cooldownExpired = stats.cooldownUntil() != null && stats.cooldownUntil() > 0 && now >= stats.cooldownUntil();
            boolean disabledExpired = stats.disabledUntil() != null && stats.disabledUntil() > 0 && now >= stats.disabledUntil();

            if (cooldownExpired || disabledExpired) {
                Long newCooldownUntil = cooldownExpired ? null : stats.cooldownUntil();
                Long newDisabledUntil = disabledExpired ? null : stats.disabledUntil();
                String newDisabledReason = disabledExpired ? null : stats.disabledReason();
                Integer newErrorCount = (newCooldownUntil == null && newDisabledUntil == null) ? 0 : stats.errorCount();

                ProfileUsageStats updated = new ProfileUsageStats(
                    stats.lastUsed(),
                    newCooldownUntil,
                    newDisabledUntil,
                    newDisabledReason,
                    newErrorCount,
                    stats.failureCounts(),
                    stats.lastFailureAt()
                );

                entry.setValue(updated);
                mutated = true;
            }
        }

        return mutated;
    }

    private Long resolveProfileUnusableUntil(ProfileUsageStats stats) {
        if (stats == null) return null;

        List<Long> values = new ArrayList<>();
        if (stats.cooldownUntil() != null && stats.cooldownUntil() > 0) {
            values.add(stats.cooldownUntil());
        }
        if (stats.disabledUntil() != null && stats.disabledUntil() > 0) {
            values.add(stats.disabledUntil());
        }

        return values.isEmpty() ? null : values.stream().max(Long::compare).orElse(null);
    }

    private long keepActiveWindow(Long existingUntil, long now, long recomputedUntil) {
        boolean hasActive = existingUntil != null && existingUntil > now;
        return hasActive ? existingUntil : recomputedUntil;
    }

    private long calculateCooldownMs(int errorCount) {
        int normalized = Math.max(1, errorCount);
        return Math.min(60 * 60 * 1000, 60 * 1000 * (long) Math.pow(5, Math.min(normalized - 1, 3)));
    }

    private long calculateBillingBackoffMs(int errorCount) {
        int normalized = Math.max(1, errorCount);
        long baseMs = 5 * 60 * 60 * 1000; // 5 hours
        long maxMs = 24 * 60 * 60 * 1000; // 24 hours
        return Math.min(maxMs, baseMs * (long) Math.pow(2, Math.min(normalized - 1, 10)));
    }

    // ==================== Path Resolution ====================

    private String resolveAuthStorePath() {
        return Paths.get(ConfigPaths.resolveStateDir(), AUTH_PROFILE_FILENAME).toString();
    }

    private String resolveLegacyAuthStorePath() {
        return Paths.get(ConfigPaths.resolveStateDir(), LEGACY_AUTH_FILENAME).toString();
    }

    // ==================== Utilities ====================

    private String normalizeProviderId(String provider) {
        if (provider == null || provider.isBlank()) {
            return "";
        }
        return provider.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Shutdown the manager.
     */
    public void shutdown() {
        // No persistent executor to shutdown, uses per-operation locks
    }
}
