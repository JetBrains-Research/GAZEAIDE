package io.github.gazehighlighter;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Blocking bounded HTTPS transport, used only on a worker. Never logs response bodies or credentials. */
final class GazeRemoteTransport {
    record Reply(int status, String location, String body) {}
    interface Exchange { Reply send(URI uri, String method, Map<String, String> headers, String body) throws IOException, InterruptedException; }
    private final Exchange exchange;
    GazeRemoteTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        exchange = (uri, method, headers, body) -> {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)); headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var response = client.send(request.build(), ignored -> new BoundedBody());
            return new Reply(response.statusCode(), response.headers().firstValue("Location").orElse(""), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        };
    }
    GazeRemoteTransport(Exchange exchange) { this.exchange = exchange; }
    void upload(GazeRemoteConnection connection, String token, JsonObject payload) throws IOException, InterruptedException {
        if (!connection.enabled()) throw new GazeRemoteException("Remote upload is disabled. Save and enable the connection before sending.");
        if (connection.problem() != null) throw new GazeRemoteException(connection.problem());
        if (!GazeRemoteConnection.validToken(token)) throw new GazeRemoteException("Set a participant upload token (32–256 letters, digits, underscores or hyphens).");
        boolean drive = connection.connector() == GazeRemoteConnection.Connector.GOOGLE_DRIVE;
        JsonObject body = payload;
        if (drive) { body = new JsonObject(); body.addProperty("token", token); body.add("payload", payload); }
        Map<String, String> headers = drive ? Map.of("Content-Type", "application/json", "Accept", "application/json")
                : Map.of("Content-Type", "application/json", "Accept", "application/json", "Authorization", "Bearer " + token);
        Reply reply = exchange.send(URI.create(connection.endpoint()), "POST", headers, GazeRemotePayload.encoded(body));
        if (drive && (reply.status == 302 || reply.status == 303)) {
            URI redirect;
            try { redirect = URI.create(reply.location); }
            catch (RuntimeException ex) { throw new GazeRemoteException("Invalid Google receiver response."); }
            if (!allowedGoogleRedirect(redirect)) throw new GazeRemoteException("Unexpected redirect. Check the Apps Script deployment and access settings.");
            // ContentService's response-only redirect: never repeat POST or forward the token.
            reply = exchange.send(redirect, "GET", Map.of("Accept", "application/json"), null);
        }
        if (reply.status < 200 || reply.status >= 300) throw new GazeRemoteException("Upload was not acknowledged (HTTP " + reply.status + "). Check the receiver, token and network; retry from the local queue.");
        try {
            JsonObject ack = JsonParser.parseString(reply.body).getAsJsonObject();
            if (!ack.has("ok") || !ack.get("ok").isJsonPrimitive() || !ack.getAsJsonPrimitive("ok").isBoolean()
                    || !ack.get("ok").getAsBoolean() || !payload.get("uploadId").equals(ack.get("uploadId")))
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw new GazeRemoteException("Receiver did not acknowledge this upload ID. Check deployment, participant token and receiver schema; the recording remains local."); }
    }
    static boolean allowedGoogleRedirect(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "script.googleusercontent.com".equalsIgnoreCase(uri.getHost())
                && "/macros/echo".equals(uri.getPath()) && uri.getRawUserInfo() == null && uri.getRawFragment() == null && uri.getPort() == -1;
    }
    /** Bound bytes as they arrive, rather than buffering an untrusted response before checking size. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 16384 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new GazeRemoteException("Receiver acknowledgement exceeded 16 KiB. Check the receiver configuration.")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
