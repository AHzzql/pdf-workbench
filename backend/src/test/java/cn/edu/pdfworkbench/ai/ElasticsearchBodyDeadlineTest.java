package cn.edu.pdfworkbench.ai;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ElasticsearchBodyDeadlineTest {
  static final class Subscription implements Flow.Subscription {
    boolean cancelled; long requested;
    public void request(long count) { requested += count; }
    public void cancel() { cancelled = true; }
  }
  @Test void acceptsFragmentedBodiesAtLimit() throws Exception {
    var body = new BoundedHttpBody(4); var subscription = new Subscription();
    body.onSubscribe(subscription); body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.allocate(0)));
    assertFalse(body.getBody().toCompletableFuture().isDone());
    body.onNext(List.of(ByteBuffer.wrap(new byte[]{3,4}))); body.onComplete();
    assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().get());
    assertFalse(subscription.cancelled); assertEquals(3,subscription.requested);
  }
  @Test void rejectsOversizedBodyAndCancelsUpstream() {
    var body = new BoundedHttpBody(3); var subscription = new Subscription();
    body.onSubscribe(subscription); body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3,4})));
    assertTrue(subscription.cancelled);
    assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
    var duplicate=new Subscription(); body.onSubscribe(duplicate); assertTrue(duplicate.cancelled);
  }
  @Test void stalledResponseBodyTimesOutAndSameClientRecovers() throws Exception {
    try(var fixture=new SlowServer()) {
      var index=fixture.index(Duration.ofMillis(700)); long started=System.nanoTime();
      assertEquals("SEARCH_UNAVAILABLE",assertThrows(ApiException.class,index::refresh).code());
      assertEquals(0,fixture.headers.getCount());
      assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(3))<0);
      index.refresh();
    }
  }
  @Test void interruptionCancelsBodyWaitAndPreservesThreadFlag() throws Exception {
    try(var fixture=new SlowServer()) {
      var index=fixture.index(Duration.ofSeconds(30)); var interrupted=new AtomicBoolean();
      var failure=new AtomicReference<Throwable>();
      var thread=Thread.ofPlatform().start(()-> {
        try { index.refresh(); failure.set(new AssertionError("Request unexpectedly succeeded")); }
        catch(CancellationException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
        catch(Throwable error) { failure.set(error); }
      });
      try {
        assertTrue(fixture.headers.await(3,TimeUnit.SECONDS)); thread.interrupt(); thread.join(3000);
        assertFalse(thread.isAlive()); assertNull(failure.get()); assertTrue(interrupted.get());
      } finally { fixture.release.countDown(); thread.interrupt(); thread.join(3000); }
    }
  }
  static final class SlowServer implements AutoCloseable {
    final CountDownLatch headers=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicBoolean first=new AtomicBoolean(true);
    final ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor();
    final HttpServer server;
    SlowServer() throws Exception {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(pool);
      server.createContext("/",exchange-> {
        exchange.getRequestBody().readAllBytes();
        byte[] response="{\"_shards\":{\"failed\":0}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200,response.length);
        try(var stream=exchange.getResponseBody()) {
          if(first.getAndSet(false)) {
            stream.write(response,0,1); stream.flush(); headers.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            stream.write(response,1,response.length-1);
          } else stream.write(response);
        } finally {exchange.close();}
      }); server.start();
    }
    ElasticsearchIndex index(Duration timeout) {
      return new ElasticsearchIndex(new ElasticsearchSettings("http://127.0.0.1:"+server.getAddress().getPort(),
          "fixture","fixture","pdf-workbench-deadline"),new ObjectMapper(),timeout);
    }
    public void close() {release.countDown();server.stop(0);pool.close();}
  }
}
