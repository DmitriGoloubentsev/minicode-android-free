package javax.management;

/**
 * Stub for Android compatibility.
 */
public class MBeanException extends JMException {
    private final Exception exception;

    public MBeanException(Exception e) {
        super();
        this.exception = e;
    }

    public MBeanException(Exception e, String message) {
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
