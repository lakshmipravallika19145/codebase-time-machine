package com.timemachine.codebase_time_machine;

public record Chunk(String filePath, int startLine, int endLine,
                    String kind, String name, String content) {}