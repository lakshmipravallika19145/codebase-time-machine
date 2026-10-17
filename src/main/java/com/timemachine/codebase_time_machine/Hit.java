package com.timemachine.codebase_time_machine;

public record Hit(long id, String filePath, int startLine, int endLine,
                  String kind, String name, String content, double score) {

    public Hit withScore(double newScore) {
        return new Hit(id, filePath, startLine, endLine, kind, name, content, newScore);
    }
}