package cn.edu.pdfworkbench;

import static cn.edu.pdfworkbench.ai.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiWorkspaceTest {
  @TempDir Path root;

  AiSettings settings() {
    return new AiSettings(root.toString(), "", "https://example.invalid", "https://example.invalid", "chat", "embedding", "rerank");
  }

  void save(AiRepository repository, String id, String text) throws Exception {
    Path source=Files.createTempFile(root,"source-",".pdf");
    Files.writeString(source,text);
    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
    try { repository.publishDocument(new Document(id, "sample.pdf", hash, "v1", 1, false, "READY", "", 1),source,new Content(List.of(),List.of())); }
    finally { Files.deleteIfExists(source); }
    repository.save(new Conversation(id, id, text, 1, 1));
    repository.save(new Message(id, id, "assistant", text, "COMPLETED", "", "ASK", text, null, null, List.of(), 1, 1));
  }

  @Test
  void sharedDatabasePersistsIndependentSpacesAndCascadesOnlyTheSelectedConversation() throws Exception {
    try (var database = new TestServices()) {
      var repository = new AiRepository(settings(), new ObjectMapper(), database.jdbc(),database.files());
      String id = UUID.randomUUID().toString(), a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
      try (var scope = repository.scope(a, () -> true)) { save(repository, id, "space-a"); }
      try (var scope = repository.scope(b, () -> true)) { save(repository, id, "space-b"); }
      var reopened = new AiRepository(settings(), new ObjectMapper(), database.jdbc(),database.files());
      assertThrows(ApiException.class, reopened::owner);
      try (var scope = reopened.scope(a, () -> true)) {
        assertEquals("space-a", reopened.messages(id).getFirst().text());
        Path copy=root.resolve("space-a.pdf"); reopened.pdf(id,copy);
        assertEquals("space-a",Files.readString(copy));
        assertFalse(Files.exists(reopened.legacyDocumentPath(id)));
        reopened.deleteConversation(id);
        assertThrows(ApiException.class, () -> reopened.message(id));
      }
      try (var scope = reopened.scope(b, () -> true)) {
        assertEquals("space-b", reopened.messages(id).getFirst().text());
        Path copy=root.resolve("space-b.pdf"); reopened.pdf(id,copy);
        assertEquals("space-b",Files.readString(copy));
      }
    }
  }

  @Test
  void scopeFailsClosedRestoresOnFailureAndRejectsNonCanonicalIds() {
    try (var database = new TestServices()) {
      var repository = new AiRepository(settings(), new ObjectMapper(), database.jdbc(),database.files());
      assertThrows(ApiException.class, repository::documents);
      assertThrows(IllegalStateException.class, () -> {
        try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) { throw new IllegalStateException(); }
      });
      assertThrows(ApiException.class, repository::owner);
      for (String invalid : List.of("../../outside", "guest", "------------------------------------"))
        assertThrows(ApiException.class, () -> repository.scope(invalid, () -> true));
    }
  }

  @Test
  void compositeForeignKeysRejectLinksAcrossSpaces() throws Exception {
    try (var database = new TestServices()) {
      var repository = new AiRepository(settings(), new ObjectMapper(), database.jdbc(),database.files());
      String id = UUID.randomUUID().toString();
      try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) { save(repository, id, "private"); }
      try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> repository.save(new Conversation(UUID.randomUUID().toString(), id, "invalid", 1, 1)));
      }
    }
  }

  @Test void independentRootsReadS3AndRevocationRemovesTheVerifiedCopy() throws Exception {
    try (var database=new TestServices()) {
      var repository=new AiRepository(settings(),new ObjectMapper(),database.jdbc(),database.files());
      String owner=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
      try (var scope=repository.scope(owner,()->true)) { save(repository,id,"private"); }
      var otherSettings=new AiSettings(root.resolve("different-web").toString(),"","http://127.0.0.1:9","http://127.0.0.1:9","chat","embedding","rerank");
      var other=new AiRepository(otherSettings,new ObjectMapper(),database.jdbc(),database.files());
      try (var scope=other.scope(owner,()->true)) {
        other.pdf(id,root.resolve("independent.pdf"));
        assertEquals("private",Files.readString(root.resolve("independent.pdf")));
        assertNotNull(other.content(id));
        assertFalse(Files.exists(other.root()));
      }
      var checks=new java.util.concurrent.atomic.AtomicInteger();
      try (var scope=other.scope(owner,()->checks.incrementAndGet()==1)) {
        assertEquals("CHAT_ACCESS_REVOKED",assertThrows(ApiException.class,()->other.pdf(id,root.resolve("revoked.pdf"))).code());
        assertFalse(Files.exists(root.resolve("revoked.pdf")));
      }
      checks.set(0);
      try (var scope=other.scope(owner,()->checks.incrementAndGet()==1)) {
        assertEquals(403,assertThrows(ApiException.class,()->other.content(id)).status());
      }
    }
  }

  @Test void unavailableS3DoesNotFallBackToLegacyCopiesOrPublishNewDocuments() throws Exception {
    try (var database=new TestServices()) {
      var repository=new AiRepository(settings(),new ObjectMapper(),database.jdbc(),database.files());
      String owner=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
      try (var scope=repository.scope(owner,()->true)) {
        save(repository,id,"private");
        Files.writeString(repository.legacyDocumentPath(id),"local fallback must not be read");
        Files.writeString(repository.legacyContentPath(id),"{\"chunks\":[],\"chapters\":[]}");
      }
      var settings=database.objectSettings();
      try (var objects=new cn.edu.pdfworkbench.storage.S3ObjectStore(new cn.edu.pdfworkbench.storage.S3Settings("http://127.0.0.1:9",settings.region(),settings.bucket(),settings.accessKey(),settings.secretKey()))) {
        var offline=new AiRepository(settings(),new ObjectMapper(),database.jdbc(),new cn.edu.pdfworkbench.storage.DocumentStorage(database.jdbc(),objects));
        try (var scope=offline.scope(owner,()->true)) {
          assertEquals(503,assertThrows(ApiException.class,()->offline.content(id)).status());
          assertEquals(503,assertThrows(ApiException.class,()->offline.pdf(id,root.resolve("offline.pdf"))).status());
          assertFalse(Files.exists(root.resolve("offline.pdf")));
          String failed=UUID.randomUUID().toString();
          assertEquals(503,assertThrows(ApiException.class,()->save(offline,failed,"new source")).status());
          assertEquals(404,assertThrows(ApiException.class,()->offline.document(failed)).status());
          assertEquals(1,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files WHERE state='GARBAGE'",Integer.class));
        }
      }
    }
  }

  @Test void interruptedDownloadCleansTemporaryFilesAndReleasesCapacity() throws Exception {
    try (var database=new TestServices()) {
      var repository=new AiRepository(settings(),new ObjectMapper(),database.jdbc(),database.files());
      var controller=new AiController(repository,null,null,settings(),null);
      String owner=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
      try (var scope=repository.scope(owner,()->true)) {
        save(repository,id,"private");
        var failed=new org.springframework.mock.web.MockHttpServletResponse() {
          @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
            return new jakarta.servlet.ServletOutputStream() {
              public boolean isReady(){return true;}
              public void setWriteListener(jakarta.servlet.WriteListener listener){}
              public void write(int value) throws java.io.IOException {throw new java.io.IOException("fixture disconnect");}
            };
          }
        };
        assertThrows(java.io.IOException.class,()->controller.file(id,failed));
        try (var paths=Files.list(repository.root())) {assertTrue(paths.findAny().isEmpty());}
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        controller.file(id,response);
        assertEquals("private",response.getContentAsString());
        try (var paths=Files.list(repository.root())) {assertTrue(paths.findAny().isEmpty());}
      }
    }
  }

  @Test void downloadCapacityIsHeldUntilTheResponseFinishes() throws Exception {
    try (var database=new TestServices(); var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var repository=new AiRepository(settings(),new ObjectMapper(),database.jdbc(),database.files());
      var controller=new AiController(repository,null,null,settings(),null);
      String owner=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
      try (var scope=repository.scope(owner,()->true)) {save(repository,id,"capacity");}
      var entered=new java.util.concurrent.CountDownLatch(4);
      var release=new java.util.concurrent.CountDownLatch(1);
      var running=new ArrayList<java.util.concurrent.Future<?>>();
      try {
        for(int i=0;i<4;i++)running.add(pool.submit(()->{
          try(var scope=repository.scope(owner,()->true)) {
            var response=new org.springframework.mock.web.MockHttpServletResponse(){
              final BlockingStream stream=new BlockingStream();
              class BlockingStream extends jakarta.servlet.ServletOutputStream {
                boolean waiting;
                public boolean isReady(){return true;}
                public void setWriteListener(jakarta.servlet.WriteListener listener){}
                public void write(int value) throws java.io.IOException {
                  if(!waiting){waiting=true; entered.countDown();
                    try {if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                  }
                }
              }
              @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return stream;}
            };
            controller.file(id,response); return null;
          }
        }));
        assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
        try(var scope=repository.scope(owner,()->true)) {
          assertEquals("DOWNLOAD_CAPACITY",assertThrows(ApiException.class,()->controller.file(id,new org.springframework.mock.web.MockHttpServletResponse())).code());
        }
      } finally {release.countDown();}
      for(var task:running)task.get(10,java.util.concurrent.TimeUnit.SECONDS);
      try(var scope=repository.scope(owner,()->true)) {
        var response=new org.springframework.mock.web.MockHttpServletResponse(); controller.file(id,response);
        assertEquals("capacity",response.getContentAsString());
        try(var paths=Files.list(repository.root())){assertTrue(paths.findAny().isEmpty());}
      }
    }
  }
}
