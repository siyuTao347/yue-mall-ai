package com.example.agent.rag.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorFormatTest {

    @Test
    @DisplayName("向量字面量无空格且可被 pgvector 解析")
    void literalFormat() {
        String literal = VectorFormat.toLiteral(new float[]{0.5f, -0.25f, 1.0f});
        assertEquals("[0.5,-0.25,1.0]", literal);
        assertTrue(literal.startsWith("[") && literal.endsWith("]"));
    }

    @Test
    @DisplayName("L2 归一化后模长为 1")
    void l2Normalize() {
        float[] normalized = VectorFormat.l2Normalize(new float[]{3f, 4f});
        assertEquals(0.6f, normalized[0], 1e-6);
        assertEquals(0.8f, normalized[1], 1e-6);
    }

    @Test
    @DisplayName("零向量归一化不产生 NaN")
    void normalizeZeroVector() {
        float[] normalized = VectorFormat.l2Normalize(new float[]{0f, 0f});
        assertEquals(0f, normalized[0]);
        assertEquals(0f, normalized[1]);
    }

    @Test
    @DisplayName("数值列表转换为 float 数组")
    void fromNumbers() {
        float[] vector = VectorFormat.fromNumbers(List.of(1, 2.5d, 3L));
        assertEquals(3, vector.length);
        assertEquals(2.5f, vector[1], 1e-6);
    }
}
