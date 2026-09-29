package com.example.user.dto;

import lombok.Data;

@Data
public class LoginDTO {
    private String email;
    private String usernameOrEmail;
    private String password;
    private String code;
    /**
     * PASSWORD 或 CODE
     */
    private String loginType = "PASSWORD";
}
