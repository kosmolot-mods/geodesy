package pl.kosma.geodesy;


public class GeodesyAssertionFailedException extends IllegalStateException {
    public static void geodesyAssert(boolean test) {
        geodesyAssert(test, "");
    }

    public static void geodesyAssert(boolean test, String message) {
        if (!test) {
            throw new GeodesyAssertionFailedException(String.format("Geodesy assertion failed! %s", message));
        }
    }

    public GeodesyAssertionFailedException(String message) {
        super(message);
    }
}
