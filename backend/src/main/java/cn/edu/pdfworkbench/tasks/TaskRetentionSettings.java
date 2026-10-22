package cn.edu.pdfworkbench.tasks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record TaskRetentionSettings(
    @Value("${tasks.retention.published-outbox-days:7}") int publishedOutboxDays,
    @Value("${tasks.retention.terminal-task-days:30}") int terminalTaskDays,
    @Value("${tasks.retention.dead-letter-days:30}") int deadLetterDays) {
  public TaskRetentionSettings {
    if(publishedOutboxDays<1 || publishedOutboxDays>3650 || terminalTaskDays<1 || terminalTaskDays>3650
        || deadLetterDays<1 || deadLetterDays>3650)throw new IllegalArgumentException("Invalid task retention period");
  }
}
