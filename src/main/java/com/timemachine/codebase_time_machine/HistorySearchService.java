package com.timemachine.codebase_time_machine;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class HistorySearchService {

    public record Filters(String author, LocalDate since, LocalDate until, String file) {}

    private static final int POOL = 20;
    private static final int RRF_K = 60;
    private static final String COLS =
            "id, hash, author, committed_at, message, files, additions, deletions";

    private static final RowMapper<CommitHit> MAPPER = (rs, i) -> new CommitHit(
            rs.getLong("id"), rs.getString("hash"), rs.getString("author"),
            rs.getTimestamp("committed_at").toInstant().toString(),
            rs.getString("message"), rs.getString("files"),
            rs.getInt("additions"), rs.getInt("deletions"), rs.getDouble("score"));

    private final EmbeddingService embeddings;
    private final JdbcTemplate jdbc;

    public HistorySearchService(EmbeddingService embeddings, JdbcTemplate jdbc) {
        this.embeddings = embeddings;
        this.jdbc = jdbc;
    }

    // Turns the optional filters into extra SQL conditions.
    private String clause(Filters f, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        if (f.author() != null && !f.author().isBlank()) {
            sb.append(" AND author ILIKE ?");
            args.add("%" + f.author().trim() + "%");
        }
        if (f.since() != null) {
            sb.append(" AND committed_at >= ?");
            args.add(Timestamp.from(f.since().atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (f.until() != null) {
            sb.append(" AND committed_at < ?");
            args.add(Timestamp.from(f.until().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (f.file() != null && !f.file().isBlank()) {
            sb.append(" AND files ILIKE ?");
            args.add("%" + f.file().trim() + "%");
        }
        return sb.toString();
    }

    public int count(String repo, Filters f) {
        List<Object> args = new ArrayList<>();
        args.add(repo);
        String where = clause(f, args);
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM commit_chunks WHERE repo = ?" + where, Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    // Newest commits that match the filters.
    public List<CommitHit> recent(String repo, Filters f, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(repo);
        String where = clause(f, args);
        args.add(limit);
        return jdbc.query("SELECT " + COLS + ", 0 AS score FROM commit_chunks WHERE repo = ?"
                + where + " ORDER BY committed_at DESC LIMIT ?", MAPPER, args.toArray());
    }

    public List<CommitHit> vector(String repo, String question, Filters f, int k) {
        String q = embeddings.embedQuery(question);
        List<Object> args = new ArrayList<>();
        args.add(q);
        args.add(repo);
        String where = clause(f, args);
        args.add(q);
        args.add(k);
        return jdbc.query("SELECT " + COLS + ", 1 - (embedding <=> ?::vector) AS score "
                + "FROM commit_chunks WHERE repo = ? AND embedding IS NOT NULL" + where
                + " ORDER BY embedding <=> ?::vector LIMIT ?", MAPPER, args.toArray());
    }

    public List<CommitHit> keyword(String repo, String question, Filters f, int k) {
        String tsQuery = SearchService.toTsQuery(question);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(tsQuery);
        args.add(repo);
        String where = clause(f, args);
        args.add(k);
        return jdbc.query("SELECT c.id, c.hash, c.author, c.committed_at, c.message, c.files, "
                + "c.additions, c.deletions, ts_rank_cd(c.tsv, q) AS score "
                + "FROM commit_chunks c, to_tsquery('english', ?) q "
                + "WHERE c.repo = ? AND c.tsv @@ q" + where
                + " ORDER BY score DESC LIMIT ?", MAPPER, args.toArray());
    }

    public List<CommitHit> hybrid(String repo, String question, Filters f, int k) {
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, CommitHit> byId = new HashMap<>();
        addRanks(vector(repo, question, f, POOL), fused, byId);
        addRanks(keyword(repo, question, f, POOL), fused, byId);
        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(k)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }

    // Picks which commits to show the AI: everything if few match, else the best matches.
    public List<CommitHit> pick(String repo, String question, Filters f,
                                int topK, int listAll, boolean recentMode) {
        int count = count(repo, f);
        if (count == 0) {
            return List.of();
        }
        if (recentMode || count <= listAll) {
            return recent(repo, f, listAll);
        }
        return hybrid(repo, question, f, topK);
    }

    private void addRanks(List<CommitHit> hits, Map<Long, Double> fused, Map<Long, CommitHit> byId) {
        for (int i = 0; i < hits.size(); i++) {
            CommitHit h = hits.get(i);
            fused.merge(h.id(), 1.0 / (RRF_K + i + 1), Double::sum);
            byId.putIfAbsent(h.id(), h);
        }
    }
}