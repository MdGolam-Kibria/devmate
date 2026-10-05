package dev.devmate.client.folder;

import jakarta.validation.constraints.Positive;
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
 */
@Validated
@ConfigurationProperties("devmate.agent")
public record AgentProperties(
        @DefaultValue("8") @Positive int maxToolRounds,
        @DefaultValue("30000") @Positive int maxToolResultChars,
        @DefaultValue("60") @Positive int maxListedEntries) {}
