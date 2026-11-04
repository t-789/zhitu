package org.example.lifecomposer.dto;

import lombok.Getter;
import lombok.Setter;

/** Body of {@code POST /api/auth/register}; validated by the controller so errors use the envelope. */
@Getter
@Setter
public class AuthRegisterRequest {
    private String studentId;
    private String realName;
    private String email;
    private String password;
    private String confirmPassword;
}
