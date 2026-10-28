package com.timemachine.codebase_time_machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogicTests {

    @Test
    void camelCaseIsSplitIntoSearchWords() {
        assertEquals("how | is | process | Creation | Form | used",
                SearchService.toTsQuery("how is processCreationForm used?"));
    }

    @Test
    void punctuationOnlyQuestionGivesEmptyQuery() {
        assertEquals("", SearchService.toTsQuery("???"));
    }

    @Test
    void messageWithoutReasonIsFlagged() {
        assertTrue(HistoryAnswerService.lacksReason("Fix bug"));
        assertTrue(HistoryAnswerService.lacksReason(
                "Milestone 3: clone repos with JGit and chunk code with line numbers"));
    }

    @Test
    void messageWithReasonIsNotFlagged() {
        assertFalse(HistoryAnswerService.lacksReason("Add a redirect to avoid a crash on invalid page numbers"));
        assertFalse(HistoryAnswerService.lacksReason("Use a bigger pool because the old one ran out"));
    }

    @Test
    void whyPartIsRemovedFromTheSearchQuery() {
        assertEquals("Where is a new owner saved",
                FullAnswerService.retrievalQuery("Where is a new owner saved, and why was it written this way?"));
    }

    @Test
    void whyOnlyQuestionKeepsItsFullText() {
        assertEquals("Why was auth added?", FullAnswerService.retrievalQuery("Why was auth added?"));
    }

    @Test
    void wholeProjectQuestionsGoToTheProjectMap() {
        assertTrue(ProjectMapService.isGlobalQuestion("give me the folder structure of this project"));
        assertTrue(ProjectMapService.isGlobalQuestion("explain me this project, file and folder structure"));
        assertTrue(ProjectMapService.isGlobalQuestion(
                "explain and each and every file in the project with its use in simple and clear way"));
    }

    @Test
    void ordinaryQuestionsStayWithSearch() {
        assertFalse(ProjectMapService.isGlobalQuestion("Where is a new owner saved?"));
        assertFalse(ProjectMapService.isGlobalQuestion("Where are the uploaded files stored?"));
    }

    @Test
    void fileRulesDescribeCommonFiles() {
        assertEquals("Automated tests for OwnerController.",
                FileRules.describe("src/test/java/x/OwnerControllerTests.java"));
        assertEquals("Image asset.", FileRules.describe("public/logo.png"));
        assertNull(FileRules.describe("src/main/java/x/OwnerController.java"));
    }
}