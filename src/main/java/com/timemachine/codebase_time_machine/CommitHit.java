package com.timemachine.codebase_time_machine;

public record CommitHit(long id, String hash, String author, String committedAt,
                        String message, String files, int additions, int deletions, double score) {

    public CommitHit withScore(double s) {
        return new CommitHit(id, hash, author, committedAt, message, files, additions, deletions, s);
    }
}