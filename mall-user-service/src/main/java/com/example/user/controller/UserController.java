package com.example.user.controller;

import api.util.JwtUtil;
import com.example.user.annoation.SendCodeLimit;
import com.example.user.dto.LoginDTO;
import com.example.user.dto.RegisterDTO;
import com.example.user.service.UserService;
import com.example.user.vo.AuthVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 发送邮箱验证码 (带有 60 秒限流保护与 QQ SMTP 真实外发)
     */
    @RequestMapping(value = "/sendEmailCode", method = {RequestMethod.GET, RequestMethod.POST})
    @SendCodeLimit(time = 60, message = "邮件发送太频繁，请 1 分钟后再试！")
    public Map<String, Object> sendEmailCode(@RequestParam("email") String email) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String code = userService.sendEmailVerificationCode(email);
            // 验证码只写日志，不返回给客户端，避免生产环境验证码泄露
            log.info("email verification code sent, email={}, code={}", email, code);
            resp.put("code", 200);
            resp.put("msg", "验证码已成功投递至邮箱 " + email);
            return resp;
        } catch (Exception e) {
            resp.put("code", 400);
            resp.put("msg", e.getMessage());
            return resp;
        }
    }

    /**
     * 邮箱验证码注册 (BCrypt 加密 + 签发 JWT)
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterDTO dto) {
        Map<String, Object> resp = new HashMap<>();
        try {
            AuthVO auth = userService.register(dto);
            resp.put("code", 200);
            resp.put("msg", "注册成功！已为您送上 100 初始积分大礼包");
            resp.put("data", auth);
            return resp;
        } catch (Exception e) {
            resp.put("code", 400);
            resp.put("msg", "注册失败: " + e.getMessage());
            return resp;
        }
    }

    /**
     * 用户登录 (支持 密码登录 与 验证码快捷登录)
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginDTO dto) {
        Map<String, Object> resp = new HashMap<>();
        try {
            AuthVO auth = userService.login(dto);
            resp.put("code", 200);
            resp.put("msg", "登录成功，欢迎归队！");
            resp.put("data", auth);
            return resp;
        } catch (Exception e) {
            resp.put("code", 401);
            resp.put("msg", "登录失败: " + e.getMessage());
            return resp;
        }
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/info")
    public Map<String, Object> getUserInfo(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        Long userId = JwtUtil.parseUserId(authHeader);
        if (userId == null) {
            resp.put("code", 401);
            resp.put("msg", "未登录或登录态已失效");
            return resp;
        }

        try {
            AuthVO auth = userService.getUserInfo(userId);
            resp.put("code", 200);
            resp.put("msg", "success");
            resp.put("data", auth);
            return resp;
        } catch (Exception e) {
            resp.put("code", 404);
            resp.put("msg", e.getMessage());
            return resp;
        }
    }
}
