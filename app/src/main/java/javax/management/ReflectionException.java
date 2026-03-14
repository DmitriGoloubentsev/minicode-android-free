package javax.management;

/**
 * Stub for Android compatibility. MINA SSHD references this class
 * in ExceptionUtils.peelException() but it doesn't exist on Android.
 */
public class ReflectionException extends JMException {
    private final Exception exception;

    public ReflectionException(Exception e) {
        super();
        this.exception = e;
    }

    public ReflectionException(Exception e, String message) {
        super(message);
        this.exception = e;
    }

    public Exception getTargetException() {
        return exception;
    }

    @Override
    public Throwable getCause() {
        return exception;
    }
}
