package com.example.user.service;

import api.util.JwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.user.dto.LoginDTO;
import com.example.user.dto.RegisterDTO;
import com.example.user.entity.User;
import com.example.user.entity.UserPoint;
import com.example.user.mapper.UserMapper;
import com.example.user.vo.AuthVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PointService pointService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 发送 6 位邮箱验证码并缓存至 Redis (5 分钟有效)
     */
    public String sendEmailVerificationCode(String email) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }

        // 1. 生成 6 位纯数字验证码
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 999999));

        // 2. 存入 Redis (TTL 5分钟)
        String codeKey = "email:code:" + email;
        redisTemplate.opsForValue().set(codeKey, code, Duration.ofMinutes(5));

        // 3. 异步发送真实 QQ 邮件
        emailService.sendVerificationCodeEmail(email, code);

        log.info("生成验证码成功: email={}, code={}", email, code);
        return code;
    }

    /**
     * 邮箱验证码注册 (BCrypt 加密 + 赠送 100 初始积分 + 签发 JWT)
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthVO register(RegisterDTO dto) {
        String email = dto.getEmail();
        String code = dto.getCode();
        String rawPassword = dto.getPassword();

        // 1. 验证码校验
        String codeKey = "email:code:" + email;
        String cachedCode = redisTemplate.opsForValue().get(codeKey);
        if (cachedCode == null || !cachedCode.equals(code)) {
            throw new RuntimeException("验证码无效或已过期");
        }

        // 2. 校验邮箱是否已被注册
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getEmail, email);
        if (userMapper.selectCount(query) > 0) {
            throw new RuntimeException("该邮箱已被注册，请直接登录");
        }

        // 3. BCrypt 密码加密散列
        String encodedPassword = passwordEncoder.encode(rawPassword);

        // 4. 创建用户记录
        User user = new User();
        user.setEmail(email);
        user.setPassword(encodedPassword);
        user.setNickname("特工_" + email.substring(0, Math.min(email.indexOf("@"), 8)));
        user.setAvatarUrl("https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80");
        user.setLastLoginTime(LocalDateTime.now());
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);

        // 5. 初始化用户积分资产 (赠送注册礼 100 积分)
        pointService.rewardPointsForOrder("WELCOME_BONUS_" + user.getId(), user.getId(), new java.math.BigDecimal("100.00"));
        UserPoint point = pointService.getUserPointSummary(user.getId());

        // 6. 用后即焚清理验证码
        redisTemplate.delete(codeKey);

        // 7. 签发 JWT Token
        String token = JwtUtil.generateToken(user.getId(), user.getEmail(), user.getNickname());

        log.info("用户注册成功: id={}, email={}", user.getId(), user.getEmail());
        user.setPassword(null); // 抹除密码哈希后再返回
        return AuthVO.builder().token(token).user(user).point(point).build();
    }

    /**
     * 账号登录 (支持 密码登录 与 验证码免密登录)
     */
    public AuthVO login(LoginDTO dto) {
        String email = dto.getEmail() != null ? dto.getEmail() : dto.getUsernameOrEmail();
        if (email != null) {
            email = email.trim();
        }
        String loginType = dto.getLoginType();
        if ("1".equals(loginType)) {
            loginType = "PASSWORD";
        } else if ("2".equals(loginType)) {
            loginType = "CODE";
        }
        User user;

        if ("CODE".equalsIgnoreCase(loginType)) {
            if (email == null || email.isEmpty()) {
                throw new RuntimeException("请输入登录邮箱");
            }
            // 验证码快捷免密登录
            String codeKey = "email:code:" + email;
            String cachedCode = redisTemplate.opsForValue().get(codeKey);
            if (cachedCode == null || !cachedCode.equals(dto.getCode())) {
                throw new RuntimeException("验证码无效或已过期");
            }

            LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
            query.eq(User::getEmail, email);
            user = userMapper.selectOne(query);

            if (user == null) {
                // 首次验证码登录，自动为用户注册
                user = new User();
                user.setEmail(email);
                user.setPassword(passwordEncoder.encode("VALOR_123456"));
                user.setNickname("特工_" + email.substring(0, Math.min(email.indexOf("@"), 8)));
                user.setAvatarUrl("https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80");
                user.setLastLoginTime(LocalDateTime.now());
                user.setCreateTime(LocalDateTime.now());
                userMapper.insert(user);
                pointService.rewardPointsForOrder("WELCOME_BONUS_" + user.getId(), user.getId(), new java.math.BigDecimal("100.00"));
            }
            redisTemplate.delete(codeKey);

        } else {
            // 账号 + 密码登录
            if (email == null || email.isEmpty()) {
                throw new RuntimeException("请输入登录邮箱");
            }
            LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
            query.eq(User::getEmail, email);
            user = userMapper.selectOne(query);
            if (user == null) {
                throw new RuntimeException("用户不存在，请先注册");
            }

            // 密码核验
            boolean matches = passwordEncoder.matches(dto.getPassword(), user.getPassword()) 
                    || user.getPassword().equals(dto.getPassword()); // 兼容历史明文测试账号
            if (!matches) {
                throw new RuntimeException("密码错误，请重新输入");
            }
        }

        // 更新最后登录时间
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        UserPoint point = pointService.getUserPointSummary(user.getId());
        String token = JwtUtil.generateToken(user.getId(), user.getEmail(), user.getNickname());

        user.setPassword(null);
        return AuthVO.builder().token(token).user(user).point(point).build();
    }

    /**
     * 获取用户信息
     */
    public AuthVO getUserInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        user.setPassword(null);
        UserPoint point = pointService.getUserPointSummary(userId);
        return AuthVO.builder().user(user).point(point).build();
    }
}
