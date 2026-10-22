package cn.edu.pdfworkbench.tasks;

import java.util.concurrent.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class TaskMaintenance implements SmartLifecycle {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TaskMaintenance.class);
  private final TaskStore store;
  private final TaskSettings settings;
  private final TaskRetentionSettings retention;
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("task-cleanup-reconciliation").daemon(true).factory());
  private volatile boolean running;

  public TaskMaintenance(TaskStore store,TaskSettings settings,TaskRetentionSettings retention) { this.store=store;this.settings=settings;this.retention=retention; }
  @Override public boolean isRunning() { return running; }
  @Override public void start() {
    running=true;
    if(settings.consumes())timer.scheduleWithFixedDelay(()->{
      try { store.reconcileCleanup(); }
      catch(Exception e) { if(running)log.warn("Document cleanup reconciliation pending"); }
      try { store.pruneHistory(retention); }
      catch(Exception e) { if(running)log.warn("Task history retention pending"); }
    },1,60,TimeUnit.SECONDS);
  }
  @Override public void stop() { running=false;timer.shutdownNow(); }
}
