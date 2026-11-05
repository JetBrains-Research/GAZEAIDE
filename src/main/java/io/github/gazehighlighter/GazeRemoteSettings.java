package io.github.gazehighlighter;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Local workspace preferences only. Tokens live in PasswordSafe, never XML or exports. */
@Service(Service.Level.PROJECT)
@State(name = "ImplicitGazeRemoteUpload", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GazeRemoteSettings implements PersistentStateComponent<GazeRemoteSettings.Options> {
    public static final class Options {
        public String connector = "HTTPS", endpoint = "", studyId = "", participantId = "", taskCode = "";
        public boolean enabled, automatic, acknowledged;
    }
    private Options options = new Options();
    public static GazeRemoteSettings getInstance(Project project) { return project.getService(GazeRemoteSettings.class); }
    @Override public synchronized @NotNull Options getState() { return options; }
    @Override public synchronized void loadState(@NotNull Options state) { options = state; }
    synchronized GazeRemoteConnection connection() {
        if (options.participantId.isBlank()) options.participantId = GazeRemoteConnection.defaults().participantId();
        GazeRemoteConnection.Connector connector;
        try { connector = GazeRemoteConnection.Connector.valueOf(options.connector); }
        catch (RuntimeException ex) { connector = GazeRemoteConnection.Connector.HTTPS; }
        return new GazeRemoteConnection(connector, options.endpoint, options.studyId, options.participantId,
                options.taskCode, options.enabled, options.automatic, options.acknowledged);
    }
    synchronized void save(GazeRemoteConnection connection) {
        Options next = new Options(); next.connector = connection.connector().name(); next.endpoint = connection.endpoint();
        next.studyId = connection.studyId(); next.participantId = connection.participantId(); next.taskCode = connection.taskCode();
        next.enabled = connection.enabled(); next.automatic = connection.automatic(); next.acknowledged = connection.acknowledged(); options = next;
    }
}
