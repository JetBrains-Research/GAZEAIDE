package io.github.gazehighlighter;

import java.io.IOException;

/** A plugin-authored, credential-free message that may be displayed in the upload UI. */
final class GazeRemoteException extends IOException {
    GazeRemoteException(String message) { super(message); }
}
