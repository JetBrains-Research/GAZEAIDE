package io.github.gazehighlighter;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class GazeRemoteUploadTest {
    @TempDir Path directory;
    static final String TOKEN = "a".repeat(64), ID = "d43bef80-d73e-4454-b87b-5ab97f12646f";
    static GazeRemoteConnection connection() {
        return new GazeRemoteConnection(GazeRemoteConnection.Connector.HTTPS, "https://research.example/upload", "study-1", "P01", "task-2", true, false, true);
    }
    static GazeRemoteConnection drive() {
        return new GazeRemoteConnection(GazeRemoteConnection.Connector.GOOGLE_DRIVE, "https://script.google.com/macros/s/ABC_123/exec", "study-1", "P01", "task-2", true, false, true);
    }
    static GazeSession fixture() {
        var region = new ViewedRegion("/Users/private-person/SECRET-PROJECT/private.java", 2, 8, "SECRET-LABEL", "SECRET-REGION-SOURCE", 987654321, "WEBCAM");
        var e = new GazeEvent(200000123, 123, region.filePath(), 3, 5, 90, "SECRET-LINE-SOURCE", "SECRET-IDENTIFIER", true, GazeInputMode.WEBCAM, false, region);
        var calibration = new GazeSessionMetadata.CalibrationCheck(2, 200000100, -1920, 0, 1920, 1080,
                1, 2, 3, 4, 5, 6, 12, 24, 8, 16, 7, 100, 5);
        var personal = new GazeSessionMetadata.PersonalResponse(1, 200000101, "BALANCED", true, 5, 4, .9, 300, .8, 400, 18, 4, 80);
        var tracking = new GazeSessionMetadata.TrackingConfiguration(false, "BALANCED", 85, 110, "raw-eyemu-median3-time-lowpass-line-hold-v2", calibration, personal);
        var config = new GazeSessionMetadata.InitialConfiguration("WEBCAM", "RELATIONSHIPS", GazeScenario.RELATIONSHIPS.profile(), true,
                "SECRET-PROVIDER", "SECRET-MODEL", tracking, new GazeSessionMetadata.ReviewConfiguration("COMPARE", true, 30000));
        var metadata = new GazeSessionMetadata(new GazeSessionMetadata.Labels("SECRET-NAME", "SECRET-TASK", "SECRET-CONDITION"), config);
        return new GazeSession("SECRET-PROJECT", 200000000, 200001000, List.of(e), metadata, List.of(new GazeSession.ConfigurationChange(0, false, config)));
    }
    @Test void reducedSchemaExcludesAllSourcePathsFreeTextEpochTimesAndCoefficients() throws Exception {
        JsonObject payload = GazeRemotePayload.session(fixture(), connection(), ID); String text = GazeRemotePayload.encoded(payload);
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("/Users")); assertFalse(text.contains("200000")); assertFalse(text.contains("987654321"));
        assertFalse(text.contains("checkedAtMs")); assertFalse(text.contains("createdAtMs")); assertFalse(text.contains("displayX"));
        assertEquals("f1", payload.getAsJsonArray("events").get(0).getAsJsonObject().get("fileId").getAsString());
        assertEquals("WEBCAM", payload.getAsJsonArray("events").get(0).getAsJsonObject().get("input").getAsString());
        assertEquals("task-2", payload.get("taskCode").getAsString()); assertEquals(1000, payload.get("durationMs").getAsLong());
        try (var stream = getClass().getResourceAsStream("/remote-session-v1.json")) {
            assertNotNull(stream); var expected=JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); expected.addProperty("pluginVersion","1.1.0"); assertEquals(expected, payload,
                    "Java payload and the receiver's golden fixture must agree");
        }
    }
    @Test void unknownConfigurationStringsCannotBecomeAHiddenSourceOrFreeTextChannel() {
        var config = new GazeSessionMetadata.InitialConfiguration("SECRET", "SECRET", null, false, "SECRET", "SECRET",
                new GazeSessionMetadata.TrackingConfiguration(false, "SECRET", 0, 0, "SECRET", null),
                new GazeSessionMetadata.ReviewConfiguration("SECRET", false, 0));
        var session = new GazeSession("SECRET", 1, 2, List.of(), new GazeSessionMetadata(null, config), List.of());
        String text = GazeRemotePayload.encoded(GazeRemotePayload.session(session, connection(), ID));
        assertFalse(text.contains("SECRET")); assertTrue(text.contains("UNKNOWN"));
    }
    @Test void provenanceAndFileRegionRelationshipsSurviveWithoutSource() {
        var r = fixture().events.get(0).region;
        var first = fixture().events.get(0);
        var second = new GazeEvent(200000200, 200, r.filePath(), 4, 1, 50, "SECRET", null, false, GazeInputMode.MOUSE, true, r);
        var third = new GazeEvent(200000300, 300, "/other-secret.java", 1, 0, 30, "SECRET", null, false, GazeInputMode.MOUSE);
        var s = new GazeSession("SECRET", 200000000, 200001000, List.of(first, second, third));
        var events = GazeRemotePayload.session(s, connection(), ID).getAsJsonArray("events");
        assertEquals("f1", events.get(1).getAsJsonObject().get("fileId").getAsString());
        assertEquals("r1", events.get(1).getAsJsonObject().get("regionId").getAsString());
        assertEquals("MOUSE_SIMULATION", events.get(1).getAsJsonObject().get("input").getAsString());
        assertEquals("f2", events.get(2).getAsJsonObject().get("fileId").getAsString());
        assertEquals("MOUSE_PROXY", events.get(2).getAsJsonObject().get("input").getAsString());
    }
    @Test void syntheticConnectionTestContainsNoRecording() {
        var p = GazeRemotePayload.test(connection());
        assertEquals(Set.of("protocol", "schemaVersion", "uploadId", "kind", "studyId", "participantId"), p.keySet());
        assertEquals("connection_test", p.get("kind").getAsString());
    }
    @Test void oversizedSessionIsRejectedWithoutTruncationOrOutboxEntry() throws Exception {
        var s = new GazeSession("local", 1, 2, Collections.nCopies(GazeRemotePayload.MAX_EVENTS + 1, fixture().events.get(0)));
        var outbox = new GazeRemoteOutbox(directory);
        assertThrows(IllegalArgumentException.class, () -> outbox.enqueue(s, connection())); assertTrue(outbox.entries().isEmpty());
        JsonObject p = new JsonObject(); p.addProperty("large", "x".repeat(GazeRemotePayload.MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> GazeRemotePayload.encoded(p));
    }
    @Test void emptyLegacyRecordingCanBeUploadedWithoutInventingConfiguration() {
        var p = GazeRemotePayload.session(new GazeSession("local", 1, 2, List.of()), connection(), ID);
        assertFalse(p.has("initialConfiguration")); assertEquals(0, p.getAsJsonArray("events").size());
    }
    @Test void defaultsAreLocalOnlyAndIdentifiersAreIndependentOfLocalUser() {
        var a = GazeRemoteConnection.defaults(); var b = GazeRemoteConnection.defaults();
        assertFalse(a.enabled()); assertFalse(a.automatic()); assertFalse(a.acknowledged()); assertFalse(a.canUpload());
        assertNotEquals(a.participantId(), b.participantId()); assertTrue(a.participantId().startsWith("P-"));
    }
    @Test void endpointValidationRejectsCredentialUrlsInsecureUrlsAndDriveFolderLinks() {
        for (String endpoint : List.of("http://example.org", "https://user:secret@example.org", "https://example.org/?token=secret", "https://example.org/#secret", "not an address")) {
            assertNotNull(new GazeRemoteConnection(connection().connector(), endpoint, "s", "p", "", true, false, true).problem());
        }
        assertNull(connection().problem()); assertNull(drive().problem());
        assertNotNull(new GazeRemoteConnection(drive().connector(), "https://drive.google.com/folders/123", "s", "p", "", true, false, true).problem());
        assertNotNull(new GazeRemoteConnection(connection().connector(), connection().endpoint(), "a name", "P01", "", true, false, true).problem());
        assertNotNull(new GazeRemoteConnection(connection().connector(), connection().endpoint(), "s", "P01", "", true, false, false).problem());
    }
    @Test void failedUploadCanBeRetriedAfterRestartWithSameIdAndOriginalTaskCode() throws Exception {
        var first = new GazeRemoteOutbox(directory).enqueue(fixture(), connection());
        var failed = new GazeRemoteTransport((uri, method, headers, body) -> { throw new IOException("offline"); });
        assertThrows(IOException.class, () -> failed.upload(connection(), TOKEN, first.payload()));
        var reopened = new GazeRemoteOutbox(directory); var retry = reopened.enqueue(fixture(), connection());
        assertEquals(first.payload(), retry.payload()); assertFalse(retry.sent());
        var changedTask = new GazeRemoteConnection(connection().connector(), connection().endpoint(), "study-1", "P01", "new-task", true, false, true);
        assertEquals("task-2", reopened.enqueue(fixture(), changedTask).payload().get("taskCode").getAsString());
        new GazeRemoteTransport((uri, method, headers, body) -> ack(retry.payload())).upload(connection(), TOKEN, retry.payload());
        reopened.markSent(retry); assertTrue(new GazeRemoteOutbox(directory).enqueue(fixture(), connection()).sent());
        String stored;
        try (var files = Files.list(directory)) { stored = Files.readString(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()); }
        assertFalse(stored.contains(TOKEN)); assertFalse(stored.contains("SECRET")); assertFalse(stored.contains("research.example"));
    }
    @Test void switchingStudyOrParticipantNeverReusesAnUploadIdAndClearPreservesReceipts() throws Exception {
        var outbox = new GazeRemoteOutbox(directory); var first = outbox.enqueue(fixture(), connection()); outbox.markSent(first);
        var other = new GazeRemoteConnection(connection().connector(), connection().endpoint(), "other-study", "P01", "", true, false, true);
        var second = outbox.enqueue(fixture(), other);
        assertNotEquals(first.binding(), second.binding()); assertNotEquals(first.payload().get("uploadId"), second.payload().get("uploadId"));
        outbox.clearPending(); assertEquals(1, outbox.entries().size()); assertTrue(outbox.entries().get(0).sent());
    }
    @Test void httpsConnectorUsesBearerHeaderAndDoesNotPutTokenInDataset() throws Exception {
        var p = GazeRemotePayload.test(connection());
        new GazeRemoteTransport((uri, method, headers, body) -> {
            assertEquals(URI.create(connection().endpoint()), uri); assertEquals("POST", method);
            assertEquals("Bearer " + TOKEN, headers.get("Authorization")); assertFalse(body.contains(TOKEN));
            assertEquals(p, JsonParser.parseString(body)); return ack(p);
        }).upload(connection(), TOKEN, p);
    }
    @Test void googleContentRedirectIsGetOnlyAndContainsNoTokenOrAuthorization() throws Exception {
        var p = GazeRemotePayload.test(drive()); int[] requests = {0};
        new GazeRemoteTransport((uri, method, headers, body) -> {
            if (requests[0]++ == 0) {
                assertEquals("POST", method); assertNull(headers.get("Authorization"));
                var envelope = JsonParser.parseString(body).getAsJsonObject(); assertEquals(TOKEN, envelope.get("token").getAsString()); assertEquals(p, envelope.get("payload"));
                return new GazeRemoteTransport.Reply(302, "https://script.googleusercontent.com/macros/echo?user_content_key=opaque", "");
            }
            assertEquals("GET", method); assertNull(body); assertNull(headers.get("Authorization"));
            return ack(p);
        }).upload(drive(), TOKEN, p);
        assertEquals(2, requests[0]);
    }
    @Test void arbitraryRedirectsCannotForwardCredentials() {
        for (var c : List.of(connection(), drive())) {
            int[] requests = {0};
            var transport = new GazeRemoteTransport((uri, method, headers, body) -> { requests[0]++; return new GazeRemoteTransport.Reply(302, "https://evil.example/collect", ""); });
            assertThrows(IOException.class, () -> transport.upload(c, TOKEN, GazeRemotePayload.test(c))); assertEquals(1, requests[0]);
        }
        for (String url : List.of("https://script.googleusercontent.com.evil.example/macros/echo", "http://script.googleusercontent.com/macros/echo", "https://script.googleusercontent.com/other", "https://user@script.googleusercontent.com/macros/echo"))
            assertFalse(GazeRemoteTransport.allowedGoogleRedirect(URI.create(url)));
    }
    @Test void htmlWrongIdFalseAndHttpFailuresAreNeverAcknowledgementsOrLeakedInErrors() {
        var p = GazeRemotePayload.test(connection());
        for (var reply : List.of(new GazeRemoteTransport.Reply(200, "", "<html>SECRET</html>"), new GazeRemoteTransport.Reply(200, "", "{\"ok\":true,\"uploadId\":\"wrong\",\"secret\":\"SECRET\"}"),
                new GazeRemoteTransport.Reply(200, "", "{\"ok\":false,\"secret\":\"SECRET\"}"), new GazeRemoteTransport.Reply(403, "", "SECRET"))) {
            var ex = assertThrows(IOException.class, () -> new GazeRemoteTransport((uri, method, headers, body) -> reply).upload(connection(), TOKEN, p));
            assertFalse(ex.getMessage().contains("SECRET"));
        }
    }
    @Test void noRequestIsMadeWithInvalidToken() {
        var t = new GazeRemoteTransport((uri, method, headers, body) -> { fail("Must not contact server"); return null; });
        assertThrows(IOException.class, () -> t.upload(connection(), "weak", GazeRemotePayload.test(connection())));
        assertThrows(IOException.class, () -> t.upload(connection(), TOKEN + "\n", GazeRemotePayload.test(connection())));
        var off = new GazeRemoteConnection(connection().connector(), connection().endpoint(), "study-1", "P01", "", false, false, true);
        assertThrows(IOException.class, () -> t.upload(off, TOKEN, GazeRemotePayload.test(off)));
    }
    @Test void oversizedAcknowledgementCancelsResponseBeforeBufferingIt() {
        var subscriber = new GazeRemoteTransport.BoundedBody(); boolean[] cancelled = {false};
        subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } });
        subscriber.onNext(List.of(ByteBuffer.allocate(16000))); subscriber.onNext(List.of(ByteBuffer.allocate(385)));
        assertTrue(cancelled[0]); assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void connectionPanelExplainsSetupAndKeepsUploadsOffWithoutStorage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var actions = new ArrayList<GazeRemoteUploadPanel.Action>();
            var panel = new GazeRemoteUploadPanel(GazeRemoteConnection.defaults(), "Researcher setup instructions", actions::add);
            assertFalse(panel.draft().enabled()); assertFalse(panel.draft().automatic()); assertFalse(panel.draft().acknowledged());
            assertTrue(GazeRemoteUploadPanel.NOTICE.contains("research questions")); assertTrue(GazeRemoteUploadPanel.NOTICE.contains("ethics"));
            GazeResearchPanelTest.button(panel, "remote-preview").doClick(); assertEquals(List.of(GazeRemoteUploadPanel.Action.PREVIEW), actions);
            assertEquals(0, GazeResearchPanelTest.component(panel, "remote-token", JPasswordField.class).getPassword().length);
            GazeResearchPanelPreview.prepareLayout(panel, 690, 650);
            var acknowledge = GazeResearchPanelTest.component(panel, "remote-acknowledge", JCheckBox.class);
            var point = SwingUtilities.convertPoint(acknowledge, 0, 0, panel);
            assertTrue(point.y + acknowledge.getHeight() <= panel.getHeight(), "Acknowledgement must stay visible outside the scrolling instructions");
            var save = GazeResearchPanelTest.button(panel, "remote-save");
            var savePoint = SwingUtilities.convertPoint(save, 0, 0, panel);
            assertTrue(savePoint.y + save.getHeight() <= panel.getHeight(), "Save must stay visible");
            panel.showStatus("Testing…", true); assertFalse(GazeResearchPanelTest.button(panel, "remote-upload").isEnabled());
        });
    }
    @Test void remoteOptionDoesNotChangeTheExistingSetupOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            GazeResearchPanelTest.next(panel); GazeResearchPanelTest.next(panel);
            assertEquals(GazeResearchPanel.Step.SESSION, panel.currentStep());
            GazeResearchPanelTest.button(panel, "remote-setup").doClick();
            assertEquals(List.of(GazeResearchPanel.Action.REMOTE), c.actions); assertEquals(GazeResearchPanel.Step.SESSION, panel.currentStep());
            GazeResearchPanelTest.next(panel); GazeResearchPanelTest.next(panel); GazeResearchPanelTest.next(panel);
            assertEquals(GazeResearchPanel.Step.RESULTS, panel.currentStep());
            GazeResearchPanelTest.button(panel, "remote-results").doClick(); assertEquals(GazeResearchPanel.Action.REMOTE, c.actions.get(1)); panel.dispose();
        });
    }
    private static GazeRemoteTransport.Reply ack(JsonObject p) {
        return new GazeRemoteTransport.Reply(200, "", "{\"ok\":true,\"uploadId\":\"" + p.get("uploadId").getAsString() + "\"}");
    }
}
