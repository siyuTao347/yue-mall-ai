package com.example.user.dto;

import lombok.Data;

@Data
public class RegisterDTO {
    private String email;
    private String code;
    private String password;
}
