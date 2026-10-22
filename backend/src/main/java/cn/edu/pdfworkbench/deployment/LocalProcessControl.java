package cn.edu.pdfworkbench.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Private stdin lifecycle channel for the native local process supervisor. */
@Component
@ConditionalOnProperty(name = "deployment.managed", havingValue = "true")
public final class LocalProcessControl implements DisposableBean {
  private final InputStream input;
  private final Path readyFile;
  private final Runnable close;
  private final AtomicBoolean published = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();

  @Autowired
  public LocalProcessControl(ConfigurableApplicationContext context,
      @Value("${deployment.ready-file}") String readyFile) {
    this(System.in, Path.of(readyFile), context::close);
  }

  LocalProcessControl(InputStream input, Path readyFile, Runnable close) {
    this.input = input;
    this.readyFile = readyFile;
    this.close = close;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ready() throws IOException {
    if (!started.compareAndSet(false, true)) return;
    Files.writeString(readyFile, Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
    published.set(true);
    Thread.ofVirtual().name("local-process-control").start(() -> {
      try {
        int value;
        do { value = input.read(); } while (value != -1 && value != 4);
      } catch (IOException unavailable) {
        // A lost supervisor closes the application through the normal shutdown path.
      } finally {
        destroy();
        close.run();
      }
    });
  }

  @Override public void destroy() {
    if (published.compareAndSet(true, false)) {
      try { Files.deleteIfExists(readyFile); }
      catch (IOException ignored) { /* Process ownership is checked independently of the readiness file. */ }
    }
  }
}
