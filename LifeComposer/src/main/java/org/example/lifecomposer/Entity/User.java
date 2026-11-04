package org.example.lifecomposer.Entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

@Getter
@Setter
public class User {
    private Integer id;
    private String username;
    /** Campus student id; the login account for self-registered users (nullable for legacy/admin rows). */
    private String studentId;
    private String email;
    private String realName;
    private String passwordHash;
    /**
     * type=1 -> USER, type=2 -> ADMIN
     */
    private Integer type;
    private Boolean banned;
    private Timestamp banEndTime;
    private String avatar;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    /**
     * Milestone 6 follow-up: true while an administrator-issued temporary
     * password is still in force. Such a session may only change the password.
     */
    private Boolean passwordResetRequired;

    /** Moment after which the temporary password is refused at login. */
    private Timestamp tempPasswordExpiresAt;

    /** When the password was last set (any path: register / reset / change). */
    private Timestamp passwordChangedAt;

    /**
     * Monotonic password generation counter. Sessions remember the value they
     * authenticated with; a mismatch means the password changed elsewhere and
     * the session must be dropped.
     */
    private Integer credentialVersion;
}
