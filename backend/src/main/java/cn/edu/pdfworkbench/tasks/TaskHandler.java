package cn.edu.pdfworkbench.tasks;

/** Implementations own domain context during execution and the deferred fenced commit. */
public interface TaskHandler {
  Runnable execute(TaskStore.Task task) throws Exception;
}
