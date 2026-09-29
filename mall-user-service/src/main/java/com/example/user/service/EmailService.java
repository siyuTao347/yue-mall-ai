package com.example.user.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:1677345232@qq.com}")
    private String fromEmail;

    /**
     * 异步发送 HTML 格式邮箱验证码
     */
    public void sendVerificationCodeEmail(String toEmail, String code) {
        CompletableFuture.runAsync(() -> {
            if (mailSender == null) {
                log.warn("[Email] JavaMailSender 未初始化，控制台打印验证码: to={}, code={}", toEmail, code);
                return;
            }

            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom(fromEmail, "VALOR 电竞大促中心");
                helper.setTo(toEmail);
                helper.setSubject("【VALOR】您的安全验证码: " + code);

                String htmlContent = "<div style=\"background-color:#0b0e14; color:#f0f4f8; padding:30px; font-family:'Segoe UI',sans-serif; max-width:550px; border-radius:12px; border:1px solid rgba(255,51,75,0.3);\">" +
                        "<div style=\"display:flex; align-items:center; margin-bottom:20px;\">" +
                        "  <h2 style=\"color:#ff334b; margin:0; font-size:24px; letter-spacing:2px;\">VALOR CS:GO</h2>" +
                        "  <span style=\"margin-left:10px; background:rgba(0,229,255,0.2); color:#00e5ff; font-size:11px; padding:2px 8px; border-radius:4px;\">SECURITY</span>" +
                        "</div>" +
                        "<p style=\"font-size:14px; color:#94a3b8; line-height:1.6;\">您正在进行登录/注册或敏感安全操作，您的专属动态验证码为：</p>" +
                        "<div style=\"text-align:center; margin:24px 0;\">" +
                        "  <span style=\"display:inline-block; background:rgba(255,51,75,0.15); border:1px dashed #ff334b; color:#ff334b; font-size:32px; font-weight:bold; letter-spacing:8px; padding:12px 28px; border-radius:8px;\">" + code + "</span>" +
                        "</div>" +
                        "<p style=\"font-size:12px; color:#64748b; line-height:1.5;\">⚠️ 验证码有效期为 <strong>5 分钟</strong>。若非本人操作，请忽略此邮件。<br>此为系统邮件，请勿直接回复。</p>" +
                        "<hr style=\"border:none; border-top:1px solid rgba(255,255,255,0.08); margin:20px 0;\">" +
                        "<div style=\"font-size:11px; color:#475569; text-align:center;\">&copy; 2026 VALOR Enterprise Mall. All Rights Reserved.</div>" +
                        "</div>";

                helper.setText(htmlContent, true);
                mailSender.send(message);
                log.info("✅ 验证码邮件发送成功至: {}", toEmail);
            } catch (Exception e) {
                log.error("❌ 验证码邮件发送失败: to={}, err={}", toEmail, e.getMessage(), e);
            }
        });
    }
}
