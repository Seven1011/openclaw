package ai.openclaw.common.utils;

public class OpenClawException extends RuntimeException {
    private final String code;

    public OpenClawException(String message) {
        this(null, message, null);
    }

    public OpenClawException(String code, String message) {
        this(code, message, null);
    }

    public OpenClawException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
