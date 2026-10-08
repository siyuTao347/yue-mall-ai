package com.example.agent.rag.embed;

import java.util.List;

/** 向量字面量转换：pgvector 需要 {@code [v1,v2,...]} 形式，且不能有多余空格。 */
public final class VectorFormat {

    private VectorFormat() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(vector[i]));
        }
        return builder.append(']').toString();
    }

    public static float[] fromNumbers(List<? extends Number> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    /** L2 归一化，使余弦相似度等价于点积，便于后续按距离阈值过滤。 */
    public static float[] l2Normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
