package org.example.lifecomposer.Service;

import org.example.lifecomposer.Entity.UserProfile;
import org.example.lifecomposer.Exception.ProfileApiException;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Repository.UserProfileRepository;
import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.dto.UserProfileDto;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final ProfileFieldValidator fieldValidator;
    private final CapabilityStateService capabilityStateService;
    private final UserRepository userRepository;

    public UserProfileService(UserProfileRepository userProfileRepository,
                              ProfileFieldValidator fieldValidator,
                              CapabilityStateService capabilityStateService,
                              UserRepository userRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.fieldValidator = fieldValidator;
        this.capabilityStateService = capabilityStateService;
    }

    public UserProfileDto getProfile(Long userId) {
        UserProfile profile = userProfileRepository.findByUserId(userId);
        if (profile == null) {
            return null;
        }
        return toDto(profile);
    }

    /**
     * v0.1.3: {@code users.student_id} is the authority. When the account has one, the
     * profile column is only a server-side snapshot and any client-supplied value is ignored.
     * Legacy accounts without a student id keep the old editable behaviour.
     */
    private String authoritativeStudentId(Long userId, String fallback) {
        User account = userId == null ? null : userRepository.findById(userId.intValue());
        if (account != null && account.getStudentId() != null && !account.getStudentId().isBlank()) {
            return account.getStudentId();
        }
        return fallback;
    }

    public UserProfileDto saveOrUpdate(Long userId, UserProfileDto dto) {
        UserProfile incoming = fromDto(userId, dto, true);
        Long requestedVersion = dto.getVersion();

        if (requestedVersion == null) {
            // v0.0.7 compatibility: full overwrite for old clients.
            if (!userProfileRepository.upsert(incoming)) {
                throw new ProfileApiException("PROFILE_SAVE_FAILED", "保存用户档案失败");
            }
        } else {
            UserProfile current = userProfileRepository.findByUserId(userId);
            if (current == null) {
                if (requestedVersion != 0L) {
                    throw versionConflict();
                }
                if (!userProfileRepository.insertIfAbsent(incoming)) {
                    throw versionConflict();
                }
            } else {
                if (!Objects.equals(current.getVersion(), requestedVersion)) {
                    throw versionConflict();
                }
                if (!userProfileRepository.updateWithVersion(incoming, requestedVersion)) {
                    throw versionConflict();
                }
            }
        }

        UserProfile saved = userProfileRepository.findByUserId(userId);
        if (saved == null) {
            throw new ProfileApiException("PROFILE_SAVE_FAILED", "保存后读取用户档案失败");
        }
        capabilityStateService.recordSkills(userId, saved.getSkillsJson(), saved.getExperiencesJson(), "USER_FORM");
        return toDto(saved);
    }

    private ProfileApiException versionConflict() {
        return new ProfileApiException("PROFILE_VERSION_CONFLICT",
                "画像已被其他操作更新，请刷新后重试");
    }

    private UserProfile fromDto(Long userId, UserProfileDto dto, boolean rejectDuplicates) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setCollege(fieldValidator.validateAndNormalize("college", dto.getCollege(), rejectDuplicates));
        profile.setMajor(fieldValidator.validateAndNormalize("major", dto.getMajor(), rejectDuplicates));
        profile.setGrade(fieldValidator.validateAndNormalize("grade", dto.getGrade(), rejectDuplicates));
        profile.setStudentId(authoritativeStudentId(userId,
                fieldValidator.validateAndNormalize("studentId", dto.getStudentId(), rejectDuplicates)));
        profile.setSkillsJson(fieldValidator.validateAndNormalize("skillsJson", dto.getSkillsJson(), rejectDuplicates));
        profile.setInterestsJson(fieldValidator.validateAndNormalize("interestsJson", dto.getInterestsJson(), rejectDuplicates));
        profile.setExperiencesJson(fieldValidator.validateAndNormalize("experiencesJson", dto.getExperiencesJson(), rejectDuplicates));
        profile.setPreferencesJson(fieldValidator.validateAndNormalize("preferencesJson", dto.getPreferencesJson(), rejectDuplicates));
        profile.setAvailableTime(fieldValidator.validateAndNormalize("availableTime", dto.getAvailableTime(), rejectDuplicates));
        profile.setGoals(fieldValidator.validateAndNormalize("goals", dto.getGoals(), rejectDuplicates));
        return profile;
    }

    private UserProfileDto toDto(UserProfile profile) {
        if (profile == null) {
            return null;
        }
        UserProfileDto dto = new UserProfileDto();
        dto.setCollege(profile.getCollege());
        dto.setMajor(profile.getMajor());
        dto.setGrade(profile.getGrade());
        dto.setStudentId(authoritativeStudentId(profile.getUserId(), profile.getStudentId()));
        dto.setSkillsJson(profile.getSkillsJson());
        dto.setInterestsJson(profile.getInterestsJson());
        dto.setExperiencesJson(profile.getExperiencesJson());
        dto.setPreferencesJson(profile.getPreferencesJson());
        dto.setAvailableTime(profile.getAvailableTime());
        dto.setGoals(profile.getGoals());
        dto.setVersion(profile.getVersion());
        return dto;
    }
}
