package com.tianchen.kb.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 测试用确定性向量模型（字符袋模型）。
 *
 * <p>作用：让集成测试<b>不依赖网络、不依赖本机 ONNX 模型文件、不消耗云端额度</b>，
 * 同时保留「相似文本 → 向量相近」这一关键性质，使分数方向类断言仍然有意义。
 *
 * <p>实现方式是标准的字符袋（bag-of-characters）向量：把文本映射成固定维度计数向量
 * 并做 L2 归一化。这样余弦相似度就等于两个字符串的字符分布重合度——
 * 完全相同 → 1.0；完全不共享字符 → 0.0。对「原句 vs 无关句」的方向判断足够可靠。
 *
 * <p>注意：它<b>不是</b>语义模型，中文近义改写之间不会有高分。因此涉及语义相近的
 * 断言请使用文本完全相同或高度重叠的用例。
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    /** 向量维度；取 64 位哈希空间做模运算，碰撞概率对本用途可忽略。 */
    private static final int DIMENSIONS = 512;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int index = 0; index < texts.size(); index++) {
            embeddings.add(new Embedding(embed(texts.get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        // 以字符为特征、以字符对为附加特征，增强区分度（避免不同句只差语序时分数相同）
        for (int index = 0; index < text.length(); index++) {
            vector[Math.floorMod(text.charAt(index), DIMENSIONS)] += 1.0f;
        }
        for (int index = 0; index + 1 < text.length(); index++) {
            int bigram = text.charAt(index) * 31 + text.charAt(index + 1);
            vector[Math.floorMod(bigram, DIMENSIONS)] += 1.0f;
        }
        return l2Normalize(vector);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] l2Normalize(float[] vector) {
        double sumOfSquares = 0.0d;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares == 0.0d) {
            return vector;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= norm;
        }
        return vector;
    }

    /** 便于在断言失败信息里展示文本特征（调试用）。 */
    public static String fingerprint(String text) {
        return text == null ? "<null>" : Integer.toHexString(text.hashCode())
                + "/len=" + text.getBytes(StandardCharsets.UTF_8).length;
    }
}