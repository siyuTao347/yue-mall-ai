package com.example.user.vo;

import com.example.user.entity.User;
import com.example.user.entity.UserPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthVO {
    private String token;
    private User user;
    private UserPoint point;
}
