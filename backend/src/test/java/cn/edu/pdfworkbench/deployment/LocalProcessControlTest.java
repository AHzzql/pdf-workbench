package cn.edu.pdfworkbench.deployment;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalProcessControlTest {
  @TempDir Path temp;

  @Test void unmanagedApplicationDoesNotInstallAControlChannel() {
    new ApplicationContextRunner().withUserConfiguration(LocalProcessControl.class)
        .run(context -> assertTrue(context.getBeansOfType(LocalProcessControl.class).isEmpty()));
  }

  @Test void managedApplicationInstallsControlWithoutAnHttpEndpoint() {
    new ApplicationContextRunner().withUserConfiguration(LocalProcessControl.class)
        .withPropertyValues("deployment.managed=true", "deployment.ready-file=" + temp.resolve("enabled.ready"))
        .run(context -> assertEquals(1, context.getBeansOfType(LocalProcessControl.class).size()));
  }

  @Test void readinessAndExplicitStopUseNormalCloseOnce() throws Exception {
    var stop = new CountDownLatch(1);
    var file = temp.resolve("web.ready");
    try (var output = new PipedOutputStream(); var input = new PipedInputStream(output)) {
      var control = new LocalProcessControl(input, file, stop::countDown);
      control.ready(); control.ready();
      assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(file));
      output.write(1); output.flush();
      assertFalse(stop.await(40, TimeUnit.MILLISECONDS));
      output.write(4); output.flush();
      assertTrue(stop.await(3, TimeUnit.SECONDS));
      assertFalse(Files.exists(file));
      control.destroy();
    }
  }

  @Test void supervisorEofClosesAndRemovesReadiness() throws Exception {
    var stop = new CountDownLatch(1);
    var file = temp.resolve("worker.ready");
    new LocalProcessControl(new ByteArrayInputStream(new byte[0]), file, stop::countDown).ready();
    assertTrue(stop.await(3, TimeUnit.SECONDS));
    assertFalse(Files.exists(file));
  }

  @Test void brokenChannelClosesAndRemovesReadiness() throws Exception {
    var stop = new CountDownLatch(1);
    var file = temp.resolve("broken.ready");
    new LocalProcessControl(new InputStream() {
      @Override public int read() throws IOException { throw new IOException("closed"); }
    }, file, stop::countDown).ready();
    assertTrue(stop.await(3, TimeUnit.SECONDS));
    assertFalse(Files.exists(file));
  }

  @Test void existingReadinessIsNotOverwrittenOrDeleted() throws Exception {
    var file = temp.resolve("existing.ready");
    Files.writeString(file, "other-run");
    var control = new LocalProcessControl(new ByteArrayInputStream(new byte[0]), file, () -> fail("not started"));
    assertThrows(IOException.class, control::ready);
    control.destroy();
    assertEquals("other-run", Files.readString(file));
  }
}
