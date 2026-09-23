package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.warehouse.WarehouseExploration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays src/test/parity/role-ranking.parity.mts (the ORIGINAL roleRanking.ts with the pinned BGE model) through the
 * Java port: same order, same selections, scores within 1e-4 (the golden's textLength fields are diagnostics only).
 * Model files live in backend/data/minilm-cache (gitignored). When absent they are fetched like the original's
 * scripts/download-bge-model.ts (pinned revision, SHA-256 checked); if that fails the parity test is SKIPPED.
 */
class RoleRankingParityTest {

    private static final Map<String, String> SHA256 = Map.of(
        "onnx/model_quantized.onnx", "6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4",
        "tokenizer.json", "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
        "tokenizer_config.json", "9261e7d79b44c8195c1cada2b453e55b00aeb81e907a6664974b4d7776172ab3",
        "config.json", "fa73f90bf92c8cace1fbcb709626306f2bdbc9ea3e5b5f94b440df9b6aa56350");
    // Surefire runs in target/test-work; the app's data/ dir is backend/data.
    private static final Path DIR = Path.of(System.getProperty("basedir", "."), "data", "minilm-cache", RoleRankingService.MODEL_ID, RoleRankingService.REVISION);

    @Test
    void javaRankingMatchesTypeScript() throws Exception {
        assumeTrue(ensureModel(), "BGE model files unavailable and could not be downloaded: skipped (assumption)");
        JsonNode golden = new ObjectMapper().readTree(getClass().getResourceAsStream("/parity/role-ranking.json"));
        assertEquals(RoleRankingService.MODEL_ID, golden.get("model").asText());
        assertEquals(RoleRankingService.REVISION, golden.get("revision").asText());
        RoleRankingService service = new RoleRankingService(DIR);
        for (JsonNode c : golden.get("cases")) {
            List<String> skills = strings(c.get("skills"));
            List<WarehouseExploration.Role> roles = new ArrayList<>();
            for (JsonNode r : c.get("roles")) {
                var role = new WarehouseExploration.Role();
                role.setId(r.get("id").asText());
                role.setTitle(r.get("title").asText());
                role.setDescription(r.get("description").asText());
                role.setTasks(strings(r.get("tasks")));
                roles.add(role);
            }
            String label = c.get("message").asText().substring(0, Math.min(40, c.get("message").asText().length()));
            List<RoleRankingService.Ranked> ranked = service.rank(c.get("message").asText(), skills, roles);
            JsonNode expected = c.get("ranked");
            assertEquals(expected.size(), ranked.size(), label);
            for (int i = 0; i < ranked.size(); i++) {
                assertEquals(expected.get(i).get("id").asText(), ranked.get(i).id(), label + " order @" + i);
                assertEquals(expected.get(i).get("score").asDouble(), ranked.get(i).score(), 1e-4, label + " score " + ranked.get(i).id());
            }
            assertEquals(strings(c.get("selected")), service.rankRoleCandidates(c.get("message").asText(), skills, roles), label + " selection");
        }
    }

    @Test
    void missingModelFailsLazilyAndRetriesLikeTheOriginal() {
        RoleRankingService service = new RoleRankingService(Path.of("no-such-model-dir")); // construction never loads
        var role = new WarehouseExploration.Role();
        role.setId("a"); role.setTitle("t"); role.setDescription("d");
        for (int i = 0; i < 2; i++) // not cached as a failure: each call retries and rejects
            assertThrows(IllegalStateException.class, () -> service.rankRoleCandidates("m", List.of(), List.of(role, role)));
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out;
    }

    /** Mirrors the original's scripts/download-bge-model.ts: pinned HF resolve URLs, SHA-256 verified. */
    private static boolean ensureModel() {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            for (var e : SHA256.entrySet()) {
                Path dest = DIR.resolve(e.getKey());
                if (Files.isRegularFile(dest) && sha(Files.readAllBytes(dest)).equals(e.getValue())) continue;
                URI uri = URI.create("https://huggingface.co/" + RoleRankingService.MODEL_ID + "/resolve/" + RoleRankingService.REVISION + "/" + e.getKey());
                HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri).header("User-Agent", "node").build(), HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200 || !sha(res.body()).equals(e.getValue())) return false;
                Files.createDirectories(dest.getParent());
                Files.write(dest, res.body());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
