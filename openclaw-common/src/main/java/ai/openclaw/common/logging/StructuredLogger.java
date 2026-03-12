package ai.openclaw.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Map;

public class StructuredLogger {
    private final Logger logger;
    private final String subsystem;

    private StructuredLogger(String subsystem) {
        this.subsystem = subsystem;
        this.logger = LoggerFactory.getLogger(subsystem);
    }

    public static StructuredLogger create(String subsystem) {
        return new StructuredLogger(subsystem);
    }

    public void trace(String message, Map<String, Object> meta) {
        log("TRACE", message, meta);
    }

    public void debug(String message, Map<String, Object> meta) {
        log("DEBUG", message, meta);
    }

    public void info(String message, Map<String, Object> meta) {
        log("INFO", message, meta);
    }

    public void warn(String message, Map<String, Object> meta) {
        log("WARN", message, meta);
    }

    public void error(String message, Map<String, Object> meta) {
        log("ERROR", message, meta);
    }

    public void fatal(String message, Map<String, Object> meta) {
        log("ERROR", message, meta); // SLF4J doesn't have FATAL, using ERROR
    }

    private void log(String level, String message, Map<String, Object> meta) {
        try (MDC.MDCCloseable c = MDC.putCloseable("subsystem", subsystem)) {
            if (meta != null) {
                meta.forEach((k, v) -> MDC.put(k, String.valueOf(v)));
            }
            switch (level) {
                case "TRACE" -> logger.trace(message);
                case "DEBUG" -> logger.debug(message);
                case "INFO" -> logger.info(message);
                case "WARN" -> logger.warn(message);
                case "ERROR" -> logger.error(message);
            }
        } finally {
            if (meta != null) {
                meta.keySet().forEach(MDC::remove);
            }
        }
    }

    public StructuredLogger child(String name) {
        return create(subsystem + "/" + name);
    }
    
    // Convenience methods without meta
    public void trace(String message) { trace(message, null); }
    public void debug(String message) { debug(message, null); }
    public void info(String message) { info(message, null); }
    public void warn(String message) { warn(message, null); }
    public void error(String message) { error(message, null); }
    public void fatal(String message) { fatal(message, null); }
}
