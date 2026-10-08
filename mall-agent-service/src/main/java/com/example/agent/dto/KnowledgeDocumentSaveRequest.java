package com.example.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存知识文档请求。
 *
 * @param docNo     文档编号，如 PROD-001，同一编号多次保存会生成新版本
 * @param docType   PRODUCT / RULE / POLICY / AFTER_SALE / RISK / FAQ
 * @param autoIndex 保存后是否立即切片并向量化入库
 */
public record KnowledgeDocumentSaveRequest(
        @NotBlank(message = "docNo 不能为空") @Size(max = 64, message = "docNo 最长 64") String docNo,
        @NotBlank(message = "docType 不能为空") @Size(max = 24, message = "docType 最长 24") String docType,
        @NotBlank(message = "title 不能为空") @Size(max = 128, message = "title 最长 128") String title,
        @NotBlank(message = "content 不能为空") String content,
        @Size(max = 16, message = "sourceType 最长 16") String sourceType,
        @Size(max = 255, message = "sourcePath 最长 255") String sourcePath,
        @Size(max = 16, message = "visibility 最长 16") String visibility,
        @Size(max = 16, message = "status 最长 16") String status,
        Boolean autoIndex) {
}
