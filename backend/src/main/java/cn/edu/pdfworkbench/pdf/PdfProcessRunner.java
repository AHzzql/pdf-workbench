package cn.edu.pdfworkbench.pdf;

import cn.edu.pdfworkbench.exception.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded process lifetime for a single PDF operation; request bytes are consumed and cleared. */
public final class PdfProcessRunner implements AutoCloseable {
  private static final int MAX_REQUEST_BYTES = 65536;
  private static final int MAX_WORKSPACE_FILES = 4096;
  private final Set<Process> active = ConcurrentHashMap.newKeySet();
  private final Object lifecycle = new Object();
  private volatile boolean closed;

  public void run(List<String> command, Path directory, byte[] request, Duration timeout,
      long workspaceLimit) throws IOException {
    Objects.requireNonNull(request);
    Process process = null;
    Thread writer = null;
    try {
      if (request.length > MAX_REQUEST_BYTES)
        throw new ApiException(400, "PDF_REQUEST_LIMIT", "PDF 处理参数超过大小限制");
      if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0
          || workspaceLimit < 1) throw new IllegalArgumentException("Invalid PDF process limits");
      long deadline = System.nanoTime() + timeout.toNanos();
      var builder = new ProcessBuilder(List.copyOf(command));
      builder.directory(directory.toFile());
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      configureEnvironment(builder.environment(), directory);
      synchronized (lifecycle) {
        if (closed) throw unavailable();
        try { process = builder.start(); }
        catch (IOException e) { throw unavailable(); }
        active.add(process);
      }
      Process child = process;
      var writeFailure = new AtomicReference<IOException>();
      writer = Thread.ofVirtual().name("pdf-request-writer").start(() -> {
        try (var input = child.getOutputStream()) { input.write(request); }
        catch (IOException e) { writeFailure.set(e); }
      });
      while (true) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        checkWorkspace(directory, workspaceLimit);
        if (System.nanoTime() >= deadline)
          throw new ApiException(504, "PDF_TIMEOUT", "PDF 处理超时");
        if (process.waitFor(25, TimeUnit.MILLISECONDS)) break;
      }
      checkWorkspace(directory, workspaceLimit);
      joinWriter(writer);
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (closed || writer.isAlive() || process.exitValue() != 0 || writeFailure.get() != null)
        throw new ApiException(503, "PDF_PROCESS_FAILED", "PDF 处理进程未能完成任务");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "PDF_INTERRUPTED", "PDF 处理已中断");
    } finally {
      try {
        if (process != null) {
          try { terminate(process); }
          catch (IOException failure) { closed = true; throw failure; }
          finally { if (!process.isAlive()) active.remove(process); }
        }
      } finally {
        // Terminating the child closes the pipe even when it never reads stdin.
        if (writer != null) joinWriter(writer);
        Arrays.fill(request, (byte) 0);
      }
    }
  }

  static void configureEnvironment(Map<String, String> environment, Path directory) {
    var system = new HashMap<String, String>();
    environment.forEach((name, value) -> {
      if (name.equalsIgnoreCase("SystemRoot") || name.equalsIgnoreCase("WINDIR")) system.put(name, value);
    });
    environment.clear();
    environment.putAll(system);
    environment.put("TEMP", directory.toAbsolutePath().toString());
    environment.put("TMP", directory.toAbsolutePath().toString());
  }

  private static void checkWorkspace(Path directory, long limit) throws IOException {
    long total = 0;
    int count = 0;
    try (var paths = Files.walk(directory)) {
      var iterator = paths.iterator();
      while (iterator.hasNext()) {
        Path path = iterator.next();
        if (++count > MAX_WORKSPACE_FILES) throw workspaceLimit();
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException removed) { continue; }
        if (attributes.isSymbolicLink() || attributes.isOther()) throw workspaceLimit();
        if (attributes.isRegularFile()) {
          long size = attributes.size();
          if (size > limit - total) throw workspaceLimit();
          total += size;
        }
      }
    } catch (IOException | java.io.UncheckedIOException failure) {
      throw new ApiException(500, "PDF_WORKSPACE_UNAVAILABLE", "PDF 处理工作目录无法读取");
    }
  }

  private static ApiException workspaceLimit() {
    return new ApiException(413, "PDF_WORKSPACE_LIMIT", "PDF 处理工作目录超过资源限制");
  }

  private static ApiException unavailable() {
    return new ApiException(503, "PDF_PROCESS_UNAVAILABLE", "PDF 处理进程暂不可用");
  }

  private static void joinWriter(Thread writer) {
    boolean interrupted = Thread.interrupted();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (writer.isAlive() && System.nanoTime() < deadline) {
        try { writer.join(100); }
        catch (InterruptedException e) { interrupted = true; }
      }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  private static void terminate(Process process) throws IOException {
    boolean interrupted = Thread.interrupted();
    try {
      if (!process.isAlive()) return;
      var descendants = process.descendants().toList();
      descendants.forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (process.isAlive() && System.nanoTime() < deadline) {
        try { process.waitFor(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { interrupted = true; }
      }
      if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
        throw new IOException("PDF process termination failed");
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  @Override public void close() throws IOException {
    List<Process> children;
    synchronized (lifecycle) { closed = true; children = List.copyOf(active); }
    IOException failure = null;
    for (Process process : children) {
      try { terminate(process); }
      catch (IOException e) { failure = e; }
      finally { if (!process.isAlive()) active.remove(process); }
    }
    if (failure != null) throw failure;
  }
}
