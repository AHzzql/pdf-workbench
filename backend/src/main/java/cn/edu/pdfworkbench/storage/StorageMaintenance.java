package cn.edu.pdfworkbench.storage;

import cn.edu.pdfworkbench.tasks.TaskSettings;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class StorageMaintenance implements SmartLifecycle {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StorageMaintenance.class);
  private final DocumentStorage files;
  private final TaskSettings tasks;
  private final int interval;
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("file-cleanup").daemon(true).factory());
  private volatile boolean running;

  public StorageMaintenance(DocumentStorage files, TaskSettings tasks,
      @Value("${storage.cleanup-interval-seconds:60}") int interval) {
    if (interval<1 || interval>3600) throw new IllegalArgumentException("Invalid file cleanup interval");
    this.files=files; this.tasks=tasks; this.interval=interval;
  }
  @Override public boolean isRunning() { return running; }
  @Override public void start() {
    running=true;
    if (tasks.consumes()) timer.scheduleWithFixedDelay(()->{
      try { files.reap(8); }
      catch (Exception e) { if (running) log.warn("Document file cleanup pending"); }
    },1,interval,TimeUnit.SECONDS);
  }
  @Override public void stop() { running=false; timer.shutdownNow(); }
}
