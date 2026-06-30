package tech.skullprogrammer.engine;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@ApplicationScoped
public class EmbeddingEngine {

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public void initialize(Path modelDir) throws Exception {
        log.info("[FakerSuggestion] Loading ONNX model from {}...", modelDir);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        session = env.createSession(modelDir.resolve("model.onnx").toString(), opts);
        tokenizer = HuggingFaceTokenizer.newInstance(modelDir.resolve("tokenizer.json"));
        ready.set(true);
        log.info("[FakerSuggestion] EmbeddingEngine ready");
    }

    public boolean isReady() {
        return ready.get();
    }

    public float[] embed(String text) {
        try {
            String normalized = normalizeFieldName(text);
            Encoding encoding = tokenizer.encode(normalized);

            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds = encoding.getTypeIds();
            int seqLen = inputIds.length;

            long[] shape = {1, seqLen};

            OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape);
            OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape);

            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", attentionMaskTensor,
                    "token_type_ids", tokenTypeIdsTensor
            );

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] lastHiddenState = (float[][][]) result.get("last_hidden_state").get().getValue();
                float[] pooled = meanPool(lastHiddenState[0], attentionMask);
                return l2Normalize(pooled);
            } finally {
                inputIdsTensor.close();
                attentionMaskTensor.close();
                tokenTypeIdsTensor.close();
            }
        } catch (Exception e) {
            log.warn("[FakerSuggestion] embed() failed for '{}': {}", text, e.getMessage());
            return new float[384];
        }
    }

    String normalizeFieldName(String raw) {
        if (raw == null) return "";
        // strip array notation: items[] → items
        String s = raw.replace("[]", "");
        // dotted path: use last segment only
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        // camelCase / PascalCase → spaces
        s = s.replaceAll("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ");
        // snake_case / kebab-case → spaces
        s = s.replace('_', ' ').replace('-', ' ');
        return s.toLowerCase().trim();
    }

    private float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        int hiddenSize = tokenEmbeddings[0].length;
        float[] sum = new float[hiddenSize];
        long maskSum = 0;
        for (int i = 0; i < tokenEmbeddings.length; i++) {
            if (attentionMask[i] == 1) {
                maskSum++;
                for (int j = 0; j < hiddenSize; j++) {
                    sum[j] += tokenEmbeddings[i][j];
                }
            }
        }
        if (maskSum == 0) return sum;
        for (int j = 0; j < hiddenSize; j++) {
            sum[j] /= maskSum;
        }
        return sum;
    }

    public float cosineSimilarity(float[] a, float[] b) {
        // vectors are already L2-normalized, so dot product == cosine similarity
        float dot = 0f;
        for (int i = 0; i < Math.min(a.length, b.length); i++) dot += a[i] * b[i];
        return dot;
    }

    private float[] l2Normalize(float[] v) {
        float norm = 0f;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
    }
}
