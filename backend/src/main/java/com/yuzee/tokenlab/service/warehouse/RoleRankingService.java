package com.yuzee.tokenlab.service.warehouse;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.yuzee.tokenlab.model.warehouse.WarehouseExploration;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of roleRanking.ts: the pinned local BGE encoder (Xenova/bge-small-en-v1.5 @ revision, q8 ONNX, CLS pooling,
 * L2 normalisation, 512-token window) ranks ambiguous occupation-role candidates by cosine similarity.
 * Like the original it never downloads: files must already be in data/minilm-cache/{id}/{revision}
 * (obtain them with the original's scripts/download-bge-model.ts). The model loads lazily on first use; a failed
 * load throws (retrieve() then answers UNAVAILABLE) and is retried on the next call.
 */
@Service
public class RoleRankingService {

    static final String MODEL_ID = "Xenova/bge-small-en-v1.5";
    static final String REVISION = "ea104dacec62c0de699686887e3f920caeb4f3e3";
    private static final String[] FILES = {"config.json", "tokenizer.json", "tokenizer_config.json", "onnx/model_quantized.onnx"};

    private final Path directory;
    private Encoder encoder;
    private final Map<String, float[]> vectors = new LinkedHashMap<>();

    public RoleRankingService() { this(Path.of("data", "minilm-cache", MODEL_ID, REVISION)); }

    RoleRankingService(Path directory) { this.directory = directory; }

    record Ranked(String id, double score) {}

    private record Encoder(HuggingFaceTokenizer tokenizer, OrtEnvironment env, OrtSession session) {}

    /** roleRanking.ts rankRoleCandidates(): up to 3 role ids worth keeping. */
    public List<String> rankRoleCandidates(String message, List<String> skillQueries, List<WarehouseExploration.Role> roles) throws Exception {
        return rankRoleCandidates(message, skillQueries, roles, null);
    }

    /** rankRoleCandidates with the caller's AbortSignal: a completed {@code signal} stops before the next encoding. */
    public List<String> rankRoleCandidates(String message, List<String> skillQueries, List<WarehouseExploration.Role> roles,
                                           java.util.concurrent.CompletableFuture<?> signal) throws Exception {
        List<Ranked> ranked = rank(message, skillQueries, roles, signal);
        double best = ranked.isEmpty() ? 0 : ranked.get(0).score();
        // A conservative development filter, not confidence, suitability or a calibrated probability.
        return ranked.stream().filter(r -> r.score() >= .55 && r.score() >= best - .06).limit(3).map(Ranked::id).toList();
    }

    /** All roles with their cosine score, best first; the sort is stable, like Array.prototype.sort. */
    List<Ranked> rank(String message, List<String> skillQueries, List<WarehouseExploration.Role> roles) throws Exception {
        return rank(message, skillQueries, roles, null);
    }

    private List<Ranked> rank(String message, List<String> skillQueries, List<WarehouseExploration.Role> roles,
                              java.util.concurrent.CompletableFuture<?> signal) throws Exception {
        Encoder embed = model();
        float[] q = encode(embed, (message.length() > 1800 ? message.substring(0, 1800) : message)
            + "\nSkills being explored: " + String.join(", ", skillQueries), false, signal);
        List<Ranked> ranked = new ArrayList<>();
        for (WarehouseExploration.Role role : roles) {
            List<String> tasks = role.getTasks();
            float[] v = encode(embed, role.getTitle() + ". " + role.getDescription() + ". Tasks: "
                + String.join("; ", tasks.subList(0, Math.min(3, tasks.size()))), true, signal);
            double sum = 0;
            for (int i = 0; i < v.length; i++) sum = sum + (double) v[i] * q[i];
            ranked.add(new Ranked(role.getId(), sum));
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        return ranked;
    }

    private synchronized Encoder model() {
        if (encoder != null) return encoder;
        try {
            for (String f : FILES) if (!Files.isRegularFile(directory.resolve(f))) throw new IllegalStateException("Missing local model file: " + directory.resolve(f));
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(directory.resolve("tokenizer.json"))
                .optAddSpecialTokens(true).optTruncation(false).optPadding(false).build();
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                encoder = new Encoder(tokenizer, env, env.createSession(directory.resolve("onnx/model_quantized.onnx").toString(), options));
            }
            return encoder;
        } catch (Throwable e) { // includes native-library LinkageErrors: the original rejects and retries next call
            throw new IllegalStateException("Role ranking model could not be loaded", e);
        }
    }

    private float[] encode(Encoder embed, String text, boolean cache, java.util.concurrent.CompletableFuture<?> signal) throws Exception {
        // roleRanking.ts encode(): if(signal.aborted)throw signal.reason||Error('Aborted')
        if (signal != null && signal.isDone()) throw new java.util.concurrent.CancellationException("This operation was aborted");
        // checkEmbeddingInput(tokenizer, text, 512): complete count incl. [CLS]/[SEP], no truncation.
        while (!text.isEmpty() && embed.tokenizer().encode(text).getIds().length > 512) text = text.substring(0, (int) Math.floor(text.length() * .85));
        if (cache) synchronized (vectors) { float[] hit = vectors.get(text); if (hit != null) return hit; }
        float[] vector = embedOne(embed, text);
        if (cache) synchronized (vectors) {
            if (vectors.size() >= 500) vectors.remove(vectors.keySet().iterator().next());
            vectors.put(text, vector);
        }
        return vector;
    }

    /** FeatureExtractionPipeline(text, {pooling:'cls', normalize:true}) with transformers.js's float32 arithmetic. */
    private static float[] embedOne(Encoder embed, String text) throws Exception {
        Encoding enc = embed.tokenizer().encode(text);
        Map<String, long[]> all = Map.of("input_ids", enc.getIds(), "attention_mask", enc.getAttentionMask(), "token_type_ids", enc.getTypeIds());
        Map<String, OnnxTensor> feeds = new HashMap<>();
        try {
            for (String name : embed.session().getInputNames()) {
                long[] ids = all.get(name);
                if (ids == null) throw new IllegalStateException("Missing the following inputs: " + name);
                feeds.put(name, OnnxTensor.createTensor(embed.env(), new long[][]{ids}));
            }
            try (OrtSession.Result out = embed.session().run(feeds)) {
                float[] cls = ((float[][][]) out.get("last_hidden_state").orElseThrow().getValue())[0][0].clone();
                float norm = 0;
                for (float x : cls) norm = (float) (norm + Math.pow(x, 2));
                norm = (float) Math.pow(norm, 0.5);
                for (int i = 0; i < cls.length; i++) cls[i] = (float) ((double) cls[i] / norm);
                return cls;
            }
        } finally {
            feeds.values().forEach(OnnxTensor::close);
        }
    }
}
