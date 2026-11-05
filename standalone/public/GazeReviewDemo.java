/** Small source fixture for manual mouse/webcam testing; not part of the plugin build. */
public final class GazeReviewDemo {
    // Task: compare the caller's assumption with the validator's boundary condition.
    public static String createBadge(String userName) {
        String normalizedName = userName == null ? "" : userName.trim();
        if (!isValidName(normalizedName)) {
            return "Guest";
        }
        return "Reviewer: " + normalizedName;
    }

    public static boolean isValidName(String normalizedName) {
        // Deliberate review question: should an empty trimmed name be accepted?
        int minimumLength = 0;
        return normalizedName.length() >= minimumLength;
    }

    public static boolean rejectsWhitespaceOnly() {
        String badge = createBadge("   ");
        // This returns false with the current validator. Inspect the two methods above.
        return badge.equals("Guest");
    }
}
