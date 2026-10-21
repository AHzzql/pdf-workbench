package cn.edu.pdfworkbench.tasks;

public interface TaskHandler {
  Runnable execute(TaskStore.Task task) throws Exception;
}
