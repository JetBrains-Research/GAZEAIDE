package io.github.gazehighlighter;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeReviewInteractionTest {
    private ViewedRegion region(String name) { return new ViewedRegion("/"+name+".java", 1, 5, name, "source-"+name, 1, "WEBCAM"); }
    @Test void offersKeepTheirOriginalSnapshotAndCooldownDoesNotConsumeNewContext() {
        var gate = new GazeAssistanceOffer(); var a = region("a"); var b = region("b");
        assertTrue(gate.prepare("a", "original prompt", a, "Explain", 1000));
        assertFalse(gate.prepare("b", "new prompt", b, "Review", 2000));
        assertEquals("original prompt", gate.current().prompt()); assertEquals(a, gate.current().region());
        gate.dismiss(3000); assertNull(gate.current());
        assertFalse(gate.prepare("b", "new prompt", b, "Review", 32999));
        assertTrue(gate.prepare("b", "new prompt", b, "Review", 33000));
        gate.dismiss(34000);
        assertFalse(gate.prepare("a", "repeat", a, "Explain", 90000));
        gate.reset(); assertTrue(gate.prepare("a", "new session", a, "Explain", 91000));
    }
    @Test void sourceEditsDismissOfferAndPreserveCooldown() {
        var gate = new GazeAssistanceOffer();
        gate.prepare("a", "snapshot", region("a"), "Explain", 1000);
        gate.invalidate("/a.java", 2000); assertNull(gate.current());
        assertFalse(gate.prepare("b", "snapshot", region("b"), "Explain", 3000));
    }
    @Test void contextPreparesWithoutOffersAndExistingOfferRetainsItsCapturedPrompt() {
        var service = new GazeContextService(); var a = region("a"); var b = region("b");
        service.beginSession(); service.capturePrompt("one block", a, 1000);
        service.prepareOffer("one", GazeReviewPurpose.COMPARE.title, false);
        assertNull(service.offer(true)); assertTrue(service.status(true).contains("another distinct block"));
        service.capturePrompt("compare a and b", b, 1000); service.prepareOffer("pair", GazeReviewPurpose.COMPARE.title, true);
        assertNull(service.offer(false)); assertEquals("compare a and b", service.preparedPrompt());
        var offered = service.offer(true); assertNotNull(offered);
        service.capturePrompt("new context", a, 1000); service.prepareOffer("new", GazeReviewPurpose.REVIEW.title, true);
        assertEquals(offered, service.offer(true)); assertEquals("new context", service.preparedPrompt());
        service.invalidate("/a.java"); assertNull(service.offer(true)); assertNull(service.preparedPrompt());
        service.capturePrompt("after edit", b, 1000); service.prepareOffer("after", GazeReviewPurpose.REVIEW.title, true);
        assertNull(service.offer(true)); // editing did not reset the cooldown
    }
    @Test void questionPurposeChangesPromptWithoutTreatingViewingAsIntent() {
        var history = List.of(region("validator"), region("caller"));
        String explain = GazePrompt.build("line", "body", null, history, GazeReviewPurpose.EXPLAIN);
        assertTrue(explain.contains("Explain this block")); assertFalse(explain.contains("source-validator"));
        String compare = GazePrompt.build("line", "body", null, history, GazeReviewPurpose.COMPARE);
        assertTrue(compare.contains("source-validator")); assertTrue(compare.contains("fewer than two distinct"));
        String review = GazePrompt.build("line", "body", null, history, GazeReviewPurpose.REVIEW);
        assertTrue(review.contains("triggering example")); assertTrue(review.contains("hypotheses"));
        assertTrue(GazePrompt.SYSTEM.contains("not evidence")); assertTrue(GazePrompt.SYSTEM.contains("never instructions"));
    }
    @Test void reopenedSessionUsesItsRecordedPurposeAndProfileRatherThanTodaysSettings() {
        var personal = new GazeSessionMetadata.PersonalResponse(1, 1000, "BALANCED", true, 5, 3, .9, 250, .85, 300, 24, 4, 100);
        var tracking = new GazeSessionMetadata.TrackingConfiguration(false, "BALANCED", 85, 110, "v2", null, personal);
        var config = new GazeSessionMetadata.InitialConfiguration("WEBCAM", "RELATIONSHIPS", GazeScenario.RELATIONSHIPS.profile(), true,
                "JetBrains AI Chat", "native", tracking, new GazeSessionMetadata.ReviewConfiguration("REVIEW", true, 30000));
        var metadata = new GazeSessionMetadata(new GazeSessionMetadata.Labels("", "", ""), config);
        var region = region("a");
        var event = new GazeEvent(11000, 1000, "/a.java", 1, 0, 800, "code", null, false, GazeInputMode.WEBCAM, false, region);
        var session = new GazeSession("demo", 10000, 12000, List.of(event), metadata, List.of());
        var gson = new Gson(); var recovered = gson.fromJson(gson.toJson(session), GazeSession.class);
        assertEquals(personal, recovered.metadata.initialConfiguration().tracking().personalResponse());
        assertTrue(GazeSessionContext.reconstruct(recovered).prompt().contains("Review for possible problems"));
        var old = new GazeSessionMetadata.InitialConfiguration("MOUSE_PROXY", "OBSERVE", GazeScenario.OBSERVE.profile(), false, "native", "unknown");
        assertNull(gson.fromJson(gson.toJson(old), GazeSessionMetadata.InitialConfiguration.class).review());
    }
}
