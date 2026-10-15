package dev.devmate.client.agent;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limits for answering questions with tools.
 *
 * @param maxToolRounds      model ↔ tool round trips per question before DevMate stops it
 * @param maxToolResultChars longest tool output passed back to the model; the rest is cut off (protects the context
 *                           window of small local models)
 * @param maxListedEntries   top-level entries of the folder put into the system prompt
 * @param maxHistoryMessages earlier messages (questions and answers) sent with a question; 0 switches memory off
 * @param maxHistoryChars    total length of those messages; older ones are dropped first
 */
@Validated
@ConfigurationProperties("devmate.agent")
public record AgentProperties(
        @DefaultValue("8") @Positive int maxToolRounds,
        @DefaultValue("30000") @Positive int maxToolResultChars,
        @DefaultValue("60") @Positive int maxListedEntries,
        @DefaultValue("12") @PositiveOrZero int maxHistoryMessages,
        @DefaultValue("16000") @PositiveOrZero int maxHistoryChars) {}
