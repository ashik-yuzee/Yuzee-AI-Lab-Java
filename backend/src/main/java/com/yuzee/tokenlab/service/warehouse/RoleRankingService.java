package com.yuzee.tokenlab.service.warehouse;

import com.yuzee.tokenlab.model.warehouse.WarehouseExploration;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ranks ambiguous occupation-role candidates against the user's message and requested skills.
 * <p>
 * // ponytail deviation: the old app (roleRanking.ts) ran a pinned local BGE sentence-embedding
 * // model (cosine similarity of encoded vectors) for this. This Java port has no embedding model
 * // available, so it falls back to lexical/keyword-overlap scoring (Jaccard-style overlap between
 * // message+skill tokens and each role's title/description/tasks tokens) instead of true semantic
 * // similarity. This is a documented behavioural deviation, not a bug: wire in a real embedding
 * // model (e.g. an ONNX runtime + a local MiniLM/BGE model) here if semantic ranking is needed.
 */
@Service
public class RoleRankingService {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final double MIN_SCORE = 0.12;
    private static final double MARGIN = 0.35;

    /** Mirrors roleRanking.ts's rankRoleCandidates(): returns up to 3 role ids worth keeping. */
    public List<String> rankRoleCandidates(String message, List<String> skillQueries, List<WarehouseExploration.Role> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        Set<String> queryTokens = tokens(message.length() > 1800 ? message.substring(0, 1800) : message);
        for (String skill : skillQueries == null ? List.<String>of() : skillQueries) queryTokens.addAll(tokens(skill));
        if (queryTokens.isEmpty()) return roles.stream().limit(3).map(WarehouseExploration.Role::getId).toList();

        List<double[]> scored = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (WarehouseExploration.Role role : roles) {
            StringBuilder sb = new StringBuilder();
            sb.append(role.getTitle()).append(' ').append(role.getDescription()).append(' ');
            List<String> tasks = role.getTasks();
            for (int i = 0; i < Math.min(3, tasks == null ? 0 : tasks.size()); i++) sb.append(tasks.get(i)).append(' ');
            Set<String> roleTokens = tokens(sb.toString());
            double score = overlapScore(queryTokens, roleTokens);
            ids.add(role.getId());
            scored.add(new double[]{score});
        }
        double best = scored.stream().mapToDouble(s -> s[0]).max().orElse(0);

        List<String> ranked = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(scored.get(b)[0], scored.get(a)[0]));
        // Conservative development filter, not confidence, suitability or a calibrated probability
        // (kept from the original comment: same intent, lexical score instead of cosine similarity).
        for (int i : order) {
            if (ranked.size() >= 3) break;
            double s = scored.get(i)[0];
            if (s >= MIN_SCORE && s >= best - MARGIN) ranked.add(ids.get(i));
        }
        return ranked;
    }

    private double overlapScore(Set<String> query, Set<String> candidate) {
        if (query.isEmpty() || candidate.isEmpty()) return 0;
        long shared = candidate.stream().filter(query::contains).count();
        Set<String> union = new LinkedHashSet<>(query);
        union.addAll(candidate);
        return union.isEmpty() ? 0 : (double) shared / union.size();
    }

    private Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = WORD.matcher(text.toLowerCase());
        while (m.find()) out.add(m.group());
        return out;
    }
}
