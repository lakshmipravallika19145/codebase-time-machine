package com.timemachine.codebase_time_machine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FileRules {

    private static final Pattern TEST_NAME =
            Pattern.compile("^(.*?)(Tests?|IT|\\.test|\\.spec)\\.(java|js|jsx|ts|tsx)$");

    private FileRules() {}

    // A one-line description, or null when the file needs a real look at its code.
    static String describe(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String lower = name.toLowerCase();

        String exact = switch (lower) {
            case "pom.xml" -> "Maven build file: the project's dependencies, plugins and Java version.";
            case "build.gradle", "settings.gradle" -> "Gradle build configuration: dependencies and build settings.";
            case "package.json" -> "Node.js package file: frontend dependencies and npm scripts.";
            case "package-lock.json" -> "Lock file: exact versions of the installed npm packages.";
            case "application.properties", "application.yml", "application.yaml" ->
                    "Spring Boot configuration: server, database and application settings.";
            case "readme.md" -> "Project documentation: what the project is and how to run it.";
            case "schema.sql" -> "Database schema: table definitions.";
            case "data.sql" -> "Sample data loaded into the database.";
            case "index.html" -> "HTML entry page that loads the web app.";
            case "vite.config.ts", "vite.config.js" -> "Vite configuration for building and serving the frontend.";
            case "eslint.config.js" -> "ESLint rules for checking the frontend code style.";
            case "main.tsx", "main.jsx" -> "Frontend entry point: mounts the React app into the page.";
            case ".gitignore" -> "Lists files and folders Git should not track.";
            case ".dockerignore" -> "Files and folders Docker leaves out of the image build.";
            case "vercel.json" -> "Vercel deployment settings for the frontend.";
            case ".env.example", ".env.sample" -> "Example environment variables: copy to .env and fill in real values.";
            case ".gitattributes" -> "Git settings, such as line endings per file type.";
            case ".editorconfig" -> "Editor formatting rules shared by the team.";
            case "dockerfile" -> "Instructions for building the Docker image.";
            case "docker-compose.yml", "docker-compose.yaml" -> "Docker Compose file: runs the app and its services together.";
            case "mvnw", "mvnw.cmd" -> "Maven wrapper script: runs Maven without installing it.";
            case "maven-wrapper.properties" -> "Maven wrapper settings: which Maven version to download.";
            case "license", "license.txt", "license.md" -> "The project's software license.";
            case ".env" -> "Environment variables file (contents deliberately not shown).";
            default -> null;
        };
        if (exact != null) {
            return exact;
        }
        if (lower.startsWith("tsconfig") && lower.endsWith(".json")) {
            return "TypeScript compiler settings.";
        }

        String test = testFor(path, name);
        if (test != null) {
            return test;
        }

        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp" -> "Image asset.";
            case "svg" -> "Vector image (SVG) asset.";
            case "css" -> "Stylesheet: visual styling for the user interface.";
            case "pdf" -> "PDF document.";
            case "woff", "woff2", "ttf", "otf" -> "Font file.";
            case "mp4", "webm", "mov" -> "Video asset.";
            case "lock" -> "Lock file: exact versions of installed dependencies.";
            case "txt" -> "Plain text file.";
            default -> null;
        };
    }

    private static String testFor(String path, String name) {
        Matcher m = TEST_NAME.matcher(name);
        if (!m.matches()) {
            return null;
        }
        boolean inTestFolder = path.contains("/test/") || path.startsWith("test/") || path.contains("/__tests__/");
        boolean dottedSuffix = m.group(2).startsWith(".");
        if (!inTestFolder && !dottedSuffix) {
            return null;
        }
        String subject = m.group(1);
        return subject.isEmpty() ? "Automated tests." : "Automated tests for " + subject + ".";
    }
}