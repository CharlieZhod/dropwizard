package io.dropwizard.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.jmx.JMXConfigurator;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.util.StatusPrinter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.logback.InstrumentedAppender;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.util.Duration;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import javax.management.*;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;

public class LoggingFactory {

    private static final Duration LOGGER_CONTEXT_AWAITING_TIMEOUT = Duration.seconds(10);
    private static final Duration LOGGER_CONTEXT_AWAITING_SLEEP_TIME = Duration.milliseconds(100);
    private static final ReentrantLock MBEAN_REGISTRATION_LOCK = new ReentrantLock();
    private static final ReentrantLock CONFIGURE_LOGGING_LEVEL_LOCK = new ReentrantLock();

    // initially configure for WARN+ console logging
    public static void bootstrap() {
        bootstrap(Level.WARN);
    }

    public static void bootstrap(Level level) {
        hijackJDKLogging();

        final Logger root = getLoggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAndStopAllAppenders();

        final DropwizardLayout formatter = new DropwizardLayout(root.getLoggerContext(),
                TimeZone.getDefault());
        formatter.start();

        final ThresholdFilter filter = new ThresholdFilter();
        filter.setLevel(level.toString());
        filter.start();

        final ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.addFilter(filter);
        appender.setContext(root.getLoggerContext());

        LayoutWrappingEncoder<ILoggingEvent> layoutEncoder = new LayoutWrappingEncoder<>();
        layoutEncoder.setLayout(formatter);
        appender.setEncoder(layoutEncoder);
        appender.start();

        root.addAppender(appender);
    }

    /**
     * Acquires the logger context.
     * <p/>
     * <p>It tries to correctly acquire the logger context in the multi-threaded environment.
     * Because of the <a href="bug">http://jira.qos.ch/browse/SLF4J-167</a> a thread, that didn't
     * start initialization has a possibility to get a reference not to a real context, but to a
     * substitute.</p>
     * <p>To work around this bug we spin-loop the thread with a sensible timeout, while the
     * context is not initialized. We can't just make this method synchronized, because
     * {@code  LoggerFactory.getILoggerFactory} doesn't safely publish own state. Threads can
     * observe a stale state, even if the logger has been already initialized. That's why this
     * method is not thread-safe, but it makes the best effort to return the correct result in
     * the multi-threaded environment.</p>
     */
    private static LoggerContext getLoggerContext() {
        final long startTime = System.nanoTime();
        while (true) {
            ILoggerFactory iLoggerFactory = LoggerFactory.getILoggerFactory();
            if (iLoggerFactory instanceof LoggerContext) {
                return (LoggerContext) iLoggerFactory;
            }
            if ((System.nanoTime() - startTime) > LOGGER_CONTEXT_AWAITING_TIMEOUT.toNanoseconds()) {
                throw new IllegalStateException("Unable to acquire the logger context");
            }
            try {
                Thread.sleep(LOGGER_CONTEXT_AWAITING_SLEEP_TIME.toMilliseconds());
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void hijackJDKLogging() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @NotNull
    private Level level = Level.INFO;

    @NotNull
    private ImmutableMap<String, Level> loggers = ImmutableMap.of();

    @Valid
    @NotNull
    private ImmutableList<AppenderFactory> appenders = ImmutableList.<AppenderFactory>of(
            new ConsoleAppenderFactory()
    );

    @JsonIgnore
    final LoggerContext loggerContext;

    @JsonIgnore
    final PrintStream configurationErrorsStream;

    public LoggingFactory() {
        this(getLoggerContext(), System.err);
    }

    @VisibleForTesting
    LoggingFactory(LoggerContext loggerContext, PrintStream configurationErrorsStream) {
        this.loggerContext = checkNotNull(loggerContext);
        this.configurationErrorsStream = checkNotNull(configurationErrorsStream);
    }

    @JsonProperty
    public Level getLevel() {
        return level;
    }

    @JsonProperty
    public void setLevel(Level level) {
        this.level = level;
    }

    @JsonProperty
    public ImmutableMap<String, Level> getLoggers() {
        return loggers;
    }

    @JsonProperty
    public void setLoggers(Map<String, Level> loggers) {
        this.loggers = ImmutableMap.copyOf(loggers);
    }

    @JsonProperty
    public ImmutableList<AppenderFactory> getAppenders() {
        return appenders;
    }

    @JsonProperty
    public void setAppenders(List<AppenderFactory> appenders) {
        this.appenders = ImmutableList.copyOf(appenders);
    }

    public void configure(MetricRegistry metricRegistry, String name) {
        hijackJDKLogging();

        CONFIGURE_LOGGING_LEVEL_LOCK.lock();
        final Logger root;
        try {
            root = configureLevels();
        } finally {
            CONFIGURE_LOGGING_LEVEL_LOCK.unlock();
        }

        for (AppenderFactory output : appenders) {
            root.addAppender(output.build(loggerContext, name, null));
        }

        StatusPrinter.setPrintStream(configurationErrorsStream);
        try {
            StatusPrinter.printIfErrorsOccured(loggerContext);
        } finally {
            StatusPrinter.setPrintStream(System.out);
        }

        final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        MBEAN_REGISTRATION_LOCK.lock();
        try {
            final ObjectName objectName = new ObjectName("io.dropwizard:type=Logging");
            if (!server.isRegistered(objectName)) {
                server.registerMBean(new JMXConfigurator(loggerContext,
                                server,
                                objectName),
                        objectName);
            }
        } catch (MalformedObjectNameException | InstanceAlreadyExistsException |
                NotCompliantMBeanException | MBeanRegistrationException e) {
            throw new RuntimeException(e);
        } finally {
            MBEAN_REGISTRATION_LOCK.unlock();
        }

        configureInstrumentation(root, metricRegistry);
    }

    public void stop() {
        loggerContext.stop();
    }

    private void configureInstrumentation(Logger root, MetricRegistry metricRegistry) {
        final InstrumentedAppender appender = new InstrumentedAppender(metricRegistry);
        appender.setContext(loggerContext);
        appender.start();
        root.addAppender(appender);
    }

    private Logger configureLevels() {
        final Logger root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        loggerContext.reset();

        final LevelChangePropagator propagator = new LevelChangePropagator();
        propagator.setContext(loggerContext);
        propagator.setResetJUL(true);

        loggerContext.addListener(propagator);

        root.setLevel(level);

        for (Map.Entry<String, Level> entry : loggers.entrySet()) {
            loggerContext.getLogger(entry.getKey()).setLevel(entry.getValue());
        }

        return root;
    }
}
