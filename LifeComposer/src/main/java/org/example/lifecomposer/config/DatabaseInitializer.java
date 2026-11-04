package org.example.lifecomposer.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Repository.CapabilityReferenceRepository;
import org.example.lifecomposer.Repository.CapabilityTagRepository;
import org.example.lifecomposer.Repository.ChatMessageRepository;
import org.example.lifecomposer.Repository.ChatUsageRepository;
import org.example.lifecomposer.Repository.CollegeCreditRuleRepository;
import org.example.lifecomposer.Repository.CreditActivityRepository;
import org.example.lifecomposer.Repository.FeedbackRepository;
import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.Repository.LoginLogRepository;
import org.example.lifecomposer.Repository.PlanningHistoryRepository;
import org.example.lifecomposer.Repository.ProfileChangeCandidateRepository;
import org.example.lifecomposer.Repository.RagChunkRepository;
import org.example.lifecomposer.Repository.RecommendationFeedbackRepository;
import org.example.lifecomposer.Repository.ResourceRepository;
import org.example.lifecomposer.Repository.UserCapabilityStateRepository;
import org.example.lifecomposer.Repository.UserProfileRepository;
import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.Service.AdminBootstrapService;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseInitializer {

    private static final Logger LOG = LogManager.getLogger(DatabaseInitializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final FeedbackRepository feedbackRepository;
    private final CollegeCreditRuleRepository collegeCreditRuleRepository;
    private final UserProfileRepository userProfileRepository;
    private final CreditActivityRepository creditActivityRepository;
    private final PlanningHistoryRepository planningHistoryRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ResourceRepository resourceRepository;
    private final RagChunkRepository ragChunkRepository;
    private final CapabilityTagRepository capabilityTagRepository;
    private final CapabilityReferenceRepository capabilityReferenceRepository;
    private final ChatUsageRepository chatUsageRepository;
    private final ProfileChangeCandidateRepository profileChangeCandidateRepository;
    private final UserCapabilityStateRepository userCapabilityStateRepository;
    private final RecommendationFeedbackRepository recommendationFeedbackRepository;
    private final AppSecurityProperties securityProperties;
    private final AdminBootstrapService adminBootstrapService;
    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginLogRepository loginLogRepository;
    private final boolean importMode;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate,
                               UserRepository userRepository,
                               FeedbackRepository feedbackRepository,
                               ChatMessageRepository chatMessageRepository,
                               UserProfileRepository userProfileRepository,
                               CollegeCreditRuleRepository collegeCreditRuleRepository,
                               CreditActivityRepository creditActivityRepository,
                               PlanningHistoryRepository planningHistoryRepository,
                               ResourceRepository resourceRepository,
                               RagChunkRepository ragChunkRepository,
                               CapabilityTagRepository capabilityTagRepository,
                               CapabilityReferenceRepository capabilityReferenceRepository,
                               ChatUsageRepository chatUsageRepository,
                               ProfileChangeCandidateRepository profileChangeCandidateRepository,
                               UserCapabilityStateRepository userCapabilityStateRepository,
                               RecommendationFeedbackRepository recommendationFeedbackRepository,
                               LoginAttemptRepository loginAttemptRepository,
                               LoginLogRepository loginLogRepository,
                               AppSecurityProperties securityProperties,
                               AdminBootstrapService adminBootstrapService,
                               Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.feedbackRepository = feedbackRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.userProfileRepository = userProfileRepository;
        this.collegeCreditRuleRepository = collegeCreditRuleRepository;
        this.creditActivityRepository = creditActivityRepository;
        this.planningHistoryRepository = planningHistoryRepository;
        this.resourceRepository = resourceRepository;
        this.ragChunkRepository = ragChunkRepository;
        this.capabilityTagRepository = capabilityTagRepository;
        this.capabilityReferenceRepository = capabilityReferenceRepository;
        this.chatUsageRepository = chatUsageRepository;
        this.profileChangeCandidateRepository = profileChangeCandidateRepository;
        this.userCapabilityStateRepository = userCapabilityStateRepository;
        this.recommendationFeedbackRepository = recommendationFeedbackRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.loginLogRepository = loginLogRepository;
        this.securityProperties = securityProperties;
        this.adminBootstrapService = adminBootstrapService;
        this.importMode = environment.getProperty("lifecomposer.import.mode", Boolean.class, false);
    }

    @PostConstruct
    public void init() {
        // Table creation follows SCHEMA.md 建表顺序 (dependency order).
        userRepository.createUserTableIfNeeded();
        userRepository.migrateUserSchema();
        feedbackRepository.createFeedbackTableIfNeeded();
        collegeCreditRuleRepository.createTableIfNeeded();
        userProfileRepository.createTableIfNeeded();
        userProfileRepository.migrateUserSchema();
        creditActivityRepository.createTableIfNeeded();
        planningHistoryRepository.createTableIfNeeded();
        chatMessageRepository.createChatMessageTableIfNeeded();
        chatMessageRepository.migrateSchema();
        resourceRepository.createTableIfNeeded();
        ragChunkRepository.createTableIfNeeded();
        capabilityTagRepository.createTableIfNeeded();
        capabilityReferenceRepository.createTableIfNeeded();
        chatUsageRepository.createTableIfNeeded();
        profileChangeCandidateRepository.createTableIfNeeded();
        profileChangeCandidateRepository.migrateSchema();
        userCapabilityStateRepository.createTableIfNeeded();
        recommendationFeedbackRepository.createTableIfNeeded();
        loginAttemptRepository.createTableIfNeeded();
        loginLogRepository.createTableIfNeeded();
        normalizeTimestampStorage();

        String usageCutoff = LocalDate.now(ZoneId.of(securityProperties.getChatUsageZone()))
                .minusDays(securityProperties.getChatUsageRetentionDays()).toString();
        int expiredUsageRows = chatUsageRepository.deleteOlderThan(usageCutoff);
        if (expiredUsageRows > 0) {
            LOG.info("Removed {} expired chat usage rows before {}", expiredUsageRows, usageCutoff);
        }

        createAdminConsoleIndexes();

        migrateLegacyGoals();

        bootstrapAdministrator();
    }

    /**
     * Milestone 7: indexes for the admin console filters. SQLite does not
     * auto-index foreign-key columns and the console pages filter/sort on these
     * predicates, so they are created once with {@code IF NOT EXISTS}
     * (idempotent on every startup and on already-populated databases).
     */
    private void createAdminConsoleIndexes() {
        for (String ddl : List.of(
                "CREATE INDEX IF NOT EXISTS idx_chat_messages_user_time ON chat_messages(user_id, create_time)",
                "CREATE INDEX IF NOT EXISTS idx_chat_messages_time ON chat_messages(create_time)",
                "CREATE INDEX IF NOT EXISTS idx_chat_messages_role_id ON chat_messages(role, id)",
                "CREATE INDEX IF NOT EXISTS idx_planning_user_created ON planning_history(user_id, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_planning_created ON planning_history(created_at)",
                "CREATE INDEX IF NOT EXISTS idx_planning_status ON planning_history(status)",
                "CREATE INDEX IF NOT EXISTS idx_feedback_time ON feedback(create_time)",
                "CREATE INDEX IF NOT EXISTS idx_feedback_resolved_type ON feedback(resolved, type)",
                "CREATE INDEX IF NOT EXISTS idx_chat_usage_user ON chat_usage_daily(user_id)",
                "CREATE INDEX IF NOT EXISTS idx_users_type ON users(type)",
                "CREATE INDEX IF NOT EXISTS idx_credit_rules_college ON college_credit_rules(college, credit_type)",
                "CREATE INDEX IF NOT EXISTS idx_credit_activities_verified ON credit_activities(verified)",
                "CREATE INDEX IF NOT EXISTS idx_resources_type_quality ON resources(type, data_quality)",
                "CREATE INDEX IF NOT EXISTS idx_capability_reference_section ON capability_reference(section)"
        )) {
            try {
                jdbcTemplate.execute(ddl);
            } catch (RuntimeException e) {
                // Never let an index hint block startup (e.g. missing legacy table).
                LOG.warn("Admin console index creation skipped: {}", e.getMessage());
            }
        }
    }

    /**
     * Guarded, idempotent one-time migration: merge rows still sitting in the
     * legacy `goals` table into `user_profiles.goals` (a JSON array string of
     * goal titles, per SCHEMA.md), then drop the legacy table. Soft-deleted
     * (ARCHIVED) rows are not migrated because they were already hidden from
     * the user via the old goals API. Guarding on table existence makes this a
     * no-op on fresh databases and on every start after the first successful
     * run; if it ever fails part-way, the next start retries without
     * duplicating titles (titles are de-duplicated against the profile's
     * existing JSON).
     */
    private void migrateLegacyGoals() {
        if (!tableExists("goals")) {
            return;
        }
        LOG.error("Legacy goals still exists. Please check.");
        throw new RuntimeException("Unexpected table 'goals'");
//        try {
//            Map<Long, List<String>> titlesByUser = new LinkedHashMap<>();
//            jdbcTemplate.query("""
//                            SELECT user_id, title
//                            FROM goals
//                            WHERE status != 'ARCHIVED'
//                              AND EXISTS (SELECT 1 FROM users WHERE users.id = goals.user_id)
//                            ORDER BY user_id, id
//                            """,
//                    rs -> {
//                        String title = rs.getString("title");
//                        if (title != null && !title.isBlank()) {
//                            titlesByUser
//                                    .computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>())
//                                    .add(title.trim());
//                        }
//                    });
//            for (Map.Entry<Long, List<String>> entry : titlesByUser.entrySet()) {
//                mergeLegacyGoalsIntoProfile(entry.getKey(), entry.getValue());
//            }
//            jdbcTemplate.execute("DROP TABLE goals");
//            LOG.info("Legacy goals table migrated into user_profiles.goals and dropped");
//        } catch (RuntimeException e) {
//            LOG.warn("Legacy goals migration failed and will be retried on next startup", e);
//        }
    }

//    private void mergeLegacyGoalsIntoProfile(Long userId, List<String> legacyTitles) {
//        List<String> existingColumn = jdbcTemplate.query(
//                "SELECT goals FROM user_profiles WHERE user_id = ?",
//                (rs, rowNum) -> rs.getString("goals"), userId);
//        boolean profileExists = !existingColumn.isEmpty();
//        String existingJson = profileExists ? existingColumn.get(0) : null;
//
//        LinkedHashSet<String> merged = new LinkedHashSet<>();
//        merged.addAll(parseGoalsJson(existingJson));
//        merged.addAll(legacyTitles);
//
//        String goalsJson = toGoalsJson(merged);
//        if (profileExists) {
//            jdbcTemplate.update(
//                    "UPDATE user_profiles SET goals = ?, updated_at = datetime('now') WHERE user_id = ?",
//                    goalsJson, userId);
//        } else {
//            jdbcTemplate.update("""
//                            INSERT INTO user_profiles (user_id, goals, created_at, updated_at)
//                            VALUES (?, ?, datetime('now'), datetime('now'))
//                            """,
//                    userId, goalsJson);
//        }
//    }

//    private List<String> parseGoalsJson(String goalsJson) {
//        if (goalsJson == null || goalsJson.isBlank()) {
//            return List.of();
//        }
//        try {
//            JsonNode node = MAPPER.readTree(goalsJson);
//            if (node != null && node.isArray()) {
//                List<String> titles = new ArrayList<>();
//                for (JsonNode item : node) {
//                    if (item != null && item.isTextual() && !item.asText().isBlank()) {
//                        titles.add(item.asText().trim());
//                    }
//                }
//                return titles;
//            }
//            // Not a JSON array (e.g. legacy free text): keep the raw value as a
//            // single goal title so nothing is lost during the merge.
//            return List.of(goalsJson.trim());
//        } catch (JsonProcessingException e) {
//            return List.of(goalsJson.trim());
//        }
//    }
//
//    private String toGoalsJson(Collection<String> titles) {
//        try {
//            return MAPPER.writeValueAsString(titles);
//        } catch (JsonProcessingException e) {
//            throw new IllegalStateException("Failed to serialize merged goals for user_profiles", e);
//        }
//    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    /**
     * Milestone 6: the historical fixed {@code admin/admin} bootstrap is gone.
     * The initial password must be supplied through
     * {@code LIFECOMPOSER_INITIAL_ADMIN_PASSWORD}; a missing or weak value makes
     * startup fail fast (see {@link AdminBootstrapService}). The standalone
     * import CLI never needs an administrator and therefore skips this step.
     */
    private void bootstrapAdministrator() {
        if (importMode) {
            LOG.info("Import mode active: skipping administrator bootstrap");
            return;
        }
        adminBootstrapService.bootstrap();
    }

    /**
     * v0.1.3: every TIMESTAMP/DATETIME column is stored as UTC text
     * {@code yyyy-MM-dd HH:mm:ss}. Rows written by older versions as epoch milliseconds
     * (sqlite-jdbc's default for {@code java.sql.Timestamp}) are converted in place.
     * Idempotent: only INTEGER values are touched.
     */
    void normalizeTimestampStorage() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        for (String table : tables) {
            List<String> columns = jdbcTemplate.query("PRAGMA table_info(\"" + table + "\")",
                    (rs, rowNum) -> rs.getString("type") != null
                            && (rs.getString("type").toUpperCase(java.util.Locale.ROOT).contains("TIMESTAMP")
                            || rs.getString("type").toUpperCase(java.util.Locale.ROOT).contains("DATETIME"))
                            ? rs.getString("name") : null);
            for (String column : columns) {
                if (column == null) {
                    continue;
                }
                int updated = jdbcTemplate.update("UPDATE \"" + table + "\" SET \"" + column
                        + "\" = datetime(\"" + column + "\" / 1000, 'unixepoch') WHERE typeof(\""
                        + column + "\") = 'integer'");
                if (updated > 0) {
                    LOG.info("Normalized {} epoch timestamps in {}.{} to UTC text", updated, table, column);
                }
            }
        }
    }
}
