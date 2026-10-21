package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiModelProtocolTest {
  @Test void heldProviderStillExposesItsFirstDeltaBeforeCompletion() throws Exception {
    var server=TaskNativeFixture.startModel(0);
    var first=new java.util.concurrent.CountDownLatch(1);
    TaskNativeFixture.HOLD.set(true);
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    var model=new AiModelClient(new AiSettings(".","fixture",base,base+"/rerank","chat","embed","rank"),new ObjectMapper());
    assertEquals("Alpha is 42. [p1-0]",model.complete("问题改写夹具","fixture"));
    var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var thread=Thread.startVirtualThread(()->{
      try {model.stream("你是 PDF 阅读助手。夹具","fixture",delta->{if(!delta.isBlank())first.countDown();});}catch(Throwable e){error.set(e);}
    });
    try {
      assertTrue(first.await(5,java.util.concurrent.TimeUnit.SECONDS),"No nonempty delta while provider stream is held");
      assertTrue(thread.isAlive());
    }
    finally {TaskNativeFixture.HOLD.set(false);thread.join(5000);server.stop(0);}
    assertNull(error.get());
  }

  @Test void interruptingTheTaskClosesTheHeldProviderStream() throws Exception {
    var server=TaskNativeFixture.startModel(0);
    var first=new java.util.concurrent.CountDownLatch(1);
    TaskNativeFixture.HOLD.set(true);
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    var model=new AiModelClient(new AiSettings(".","fixture",base,base+"/rerank","chat","embed","rank"),new ObjectMapper());
    var thread=Thread.startVirtualThread(()->{
      try {model.stream("你是 PDF 阅读助手。夹具","fixture",delta->{if(!delta.isBlank())first.countDown();});}
      catch(java.util.concurrent.CancellationException ignored) {}
    });
    try {
      assertTrue(first.await(5,java.util.concurrent.TimeUnit.SECONDS));
      thread.interrupt();thread.join(5000);assertFalse(thread.isAlive());
      long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while(TaskNativeFixture.ACTIVE.get()>0 && System.nanoTime()<deadline)Thread.sleep(100);
      assertEquals(0,TaskNativeFixture.ACTIVE.get(),"Cancelled HTTP stream remains open at provider");
    } finally {TaskNativeFixture.HOLD.set(false);thread.interrupt();thread.join(5000);server.stop(0);}
  }
  @Test void officialAdapterUsesOnlyTheLocalEmbeddingRankingAndStreamingFixture() throws Exception {
    var server=TaskNativeFixture.startModel(0);
    try {
      String base="http://127.0.0.1:"+server.getAddress().getPort();
      var settings=new AiSettings(".","fixture",base,base+"/rerank","chat","embed","rank");
      var model=new AiModelClient(settings,new ObjectMapper());
      var embeddings=model.embed(List.of("fixture"));
      assertEquals(1,embeddings.size()); assertEquals(1024,embeddings.getFirst().length);
      assertEquals(List.of(0),model.rerank("fixture",List.of("fixture")));
      assertEquals("Alpha is 42. [p1-0]",model.complete("fixture","fixture"));
    } finally { server.stop(0); }
  }
}
