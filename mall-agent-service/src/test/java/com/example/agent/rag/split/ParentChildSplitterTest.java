package com.example.agent.rag.split;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentChildSplitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ParentChildSplitter splitter = new ParentChildSplitter();
    private final ChunkParams params = new ChunkParams(1000, 1600, 400, 320, 450, 80, 60, 8, 32);

    private static final String SAMPLE = """
            ---
            doc_id: PROD-001
            price_cny: 1288.00
            ---

            # AK-47 | 红线 · 商品介绍

            > 本文档为平台商品知识库（RAG）初始语料，用于智能客服与仲裁助手检索。

            ## 3. 基础信息

            | 属性 | 取值 |
            |---|---|
            | 商品名称 | AK-47 | 红线 |
            | 所属游戏 | CS2 / Counter-Strike 2 |
            | 磨损档位 | 略有磨损 |
            | 审核状态 | 审核通过 |

            ## 7. 价格与费用

            商品挂牌价为单件价格，已包含卖家履约成本。平台服务费按订单金额的 2% 计收（最低 0.01 元），
            在结算冷却期结束后从托管资金中扣除，其余款项进入卖家可提现余额。

            买家的支付资金在确认收货前一直由平台托管，卖家不可直接动用。

            ## 9. 风险提示

            - 本商品为虚拟资产，不存在实体物流，成交后不支持"七天无理由"退货
            - 平台仅提供担保交易与争议仲裁服务，不承担市场价格波动带来的损失
            - 请勿脱离平台进行私下交易，否则将无法获得交付证据留存与资金托管保护
            """;

    private SplitRequest request(String content) {
        return new SplitRequest(content, "PROD-001", 1, "AK-47 | 红线 · 商品介绍", params);
    }

    @Test
    @DisplayName("按二级标题切父片，标题路径带文档标题前缀")
    void splitsByHeading() {
        SplitResult result = splitter.split(request(SAMPLE));
        assertTrue(result.parentCount() >= 3, "至少应切出前言 + 3 个小节");
        assertTrue(result.childCount() > 0, "必须有子片");

        ChunkDraft priceParent = result.parents().stream()
                .map(ParentChildDraft::parent)
                .filter(parent -> parent.titlePath().contains("7. 价格与费用"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到价格小节父片"));
        assertEquals("AK-47 | 红线 · 商品介绍/7. 价格与费用", priceParent.titlePath());
        assertTrue(priceParent.content().contains("2% 计收"), "父片应包含完整费用说明");
    }

    @Test
    @DisplayName("分片编号与层级符合命名规则")
    void chunkNaming() {
        SplitResult result = splitter.split(request(SAMPLE));
        for (int i = 0; i < result.parents().size(); i++) {
            ParentChildDraft draft = result.parents().get(i);
            String expectedParentNo = "PROD-001-v1-P%03d".formatted(i + 1);
            assertEquals(expectedParentNo, draft.parent().chunkNo());
            assertEquals(1, draft.parent().level());
            for (int j = 0; j < draft.children().size(); j++) {
                ChunkDraft child = draft.children().get(j);
                assertEquals(expectedParentNo + "-C%02d".formatted(j + 1), child.chunkNo());
                assertEquals(2, child.level());
                assertEquals(draft.parent().titlePath(), child.titlePath());
            }
        }
    }

    @Test
    @DisplayName("切片是纯函数：同输入必须得到完全相同的输出")
    void deterministic() {
        SplitResult first = splitter.split(request(SAMPLE));
        SplitResult second = splitter.split(request(SAMPLE));
        assertEquals(first.parentCount(), second.parentCount());
        assertEquals(first.childCount(), second.childCount());
        for (int i = 0; i < first.parents().size(); i++) {
            assertEquals(first.parents().get(i).parent(), second.parents().get(i).parent());
            assertEquals(first.parents().get(i).children(), second.parents().get(i).children());
        }
    }

    @Test
    @DisplayName("子片偏移与正文一致，可直接从归一化正文截取")
    void offsetsAreConsistent() {
        SplitResult result = splitter.split(request(SAMPLE));
        String normalized = result.normalizedContent();
        for (ParentChildDraft draft : result.parents()) {
            assertOffsets(normalized, draft.parent());
            for (ChunkDraft child : draft.children()) {
                assertOffsets(normalized, child);
            }
        }
    }

    private void assertOffsets(String normalized, ChunkDraft draft) {
        assertTrue(draft.charStart() >= 0 && draft.charEnd() > draft.charStart(),
                "偏移非法: " + draft.chunkNo());
        assertEquals(draft.content(), normalized.substring(draft.charStart(), draft.charEnd()),
                "偏移与正文不一致: " + draft.chunkNo());
        assertEquals(draft.charEnd() - draft.charStart(), draft.charCount());
    }

    @Test
    @DisplayName("子片不包含小节标题行，标题信息由 titlePath 承载")
    void childrenExcludeHeading() {
        SplitResult result = splitter.split(request(SAMPLE));
        for (ParentChildDraft draft : result.parents()) {
            for (ChunkDraft child : draft.children()) {
                assertFalse(child.content().startsWith("## "), "子片不应以小节标题开头");
            }
        }
    }

    @Test
    @DisplayName("超长小节按块体积二次切分")
    void splitsOversizeSection() {
        String longSection = "## 5. 超长小节\n\n"
                + "这是一段用于测试超长小节二次切分的正文内容，需要重复足够多次以超过父片上限。\n\n".repeat(60);
        SplitResult result = splitter.split(request("# 测试文档\n\n" + longSection));
        long parentsWithTitle = result.parents().stream()
                .map(ParentChildDraft::parent)
                .filter(parent -> parent.titlePath().contains("5. 超长小节"))
                .count();
        assertTrue(parentsWithTitle > 1, "超长小节应被拆成多个父片");
        for (ParentChildDraft draft : result.parents()) {
            assertTrue(draft.parent().charCount() <= params.parentMaxChars() + 200,
                    "父片不应显著超过上限: " + draft.parent().charCount());
        }
    }

    @Test
    @DisplayName("表格作为不可切分块整体保留")
    void tableStaysAtomic() {
        String bigTable = "| 字段 | 说明 |\n|---|---|\n| 长字段 | " + "说明内容".repeat(150) + " |\n";
        String doc = "# 测试文档\n\n## 1. 表格小节\n\n" + bigTable;
        SplitResult result = splitter.split(request(doc));
        List<ChunkDraft> children = result.parents().stream()
                .flatMap(parent -> parent.children().stream())
                .filter(child -> child.content().contains("| 字段 | 说明 |"))
                .toList();
        assertEquals(1, children.size(), "表格应完整落在同一个子片内");
        assertTrue(children.get(0).content().contains("说明内容".repeat(150)),
                "表格内容不应被截断");
        assertTrue(children.get(0).content().length() > params.childMaxChars(),
                "表格原子块允许超过子片上限");
    }

    @Test
    @DisplayName("关键词 JSON 可解析且非空")
    void keywordsJsonParsable() throws Exception {
        SplitResult result = splitter.split(request(SAMPLE));
        ChunkDraft child = result.parents().get(0).children().get(0);
        assertNotNull(child.keywordsJson());
        var node = MAPPER.readTree(child.keywordsJson());
        assertTrue(node.isObject());
        assertTrue(node.size() > 0, "应提取到关键词");
        assertTrue(child.tokenCount() > 0);
    }

    @Test
    @DisplayName("空内容返回空切片结果")
    void emptyContent() {
        SplitResult result = splitter.split(request("   \n\n  "));
        assertEquals(0, result.parentCount());
        assertEquals(0, result.childCount());
    }
}
