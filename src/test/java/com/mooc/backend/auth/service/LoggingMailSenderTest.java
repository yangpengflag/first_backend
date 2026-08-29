package com.mooc.backend.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingMailSenderTest {

    private static final String CODE = "verification-code-uuid-v4";
    private static final String FRONTEND_BASE_URL = "http://localhost:3000";

    private LoggingMailSender sender;

    @BeforeEach
    void setUp() {
        sender = new LoggingMailSender(FRONTEND_BASE_URL, false);
        sender.clear();
    }

    @Test
    void recordsSentMailForAssertion() {
        sender.sendVerificationEmail("alice@example.com", CODE);

        assertThat(sender.getSentMails()).hasSize(1);
        assertThat(sender.getSentMails().get(0).toEmail()).isEqualTo("alice@example.com");
        assertThat(sender.getSentMails().get(0).verificationCode()).isEqualTo(CODE);
    }

    @Test
    void accumulatesMultipleDeliveriesAndClears() {
        sender.sendVerificationEmail("a@example.com", "code-1");
        sender.sendVerificationEmail("b@example.com", "code-2");
        assertThat(sender.getSentMails()).hasSize(2);

        sender.clear();
        assertThat(sender.getSentMails()).isEmpty();
    }

    /**
     * 安全断言：验证码属一次性凭证，默认配置下不得出现在日志中。
     * 若有人改为直接打印 code，此测试将失败。
     */
    @Test
    void doesNotWriteVerificationCodeToLogsByDefault() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingMailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            sender.sendVerificationEmail("alice@example.com", CODE);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains(CODE));
    }

    @Test
    void logsTheCodeOnlyWhenExplicitlyEnabled() {
        LoggingMailSender verboseSender = new LoggingMailSender(FRONTEND_BASE_URL, true);
        verboseSender.clear();

        Logger logger = (Logger) LoggerFactory.getLogger(LoggingMailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            verboseSender.sendVerificationEmail("alice@example.com", CODE);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .anyMatch(event -> event.getFormattedMessage().contains(CODE));
    }
}
