package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.ai.AiTypes.*;
import cn.edu.pdfworkbench.tasks.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiTaskHandlerProtocolTest {
  @TempDir Path directory;
  @Test void reactiveProviderCheckpointsRemainInThePersistedTasksWorkspace() throws Exception {
    var server=TaskNativeFixture.startModel(0);
    try(var database=new TestServices(); var objects=new cn.edu.pdfworkbench.storage.S3ObjectStore(database.objectSettings())) {
      String base="http://127.0.0.1:"+server.getAddress().getPort();
      var settings=new AiSettings(directory.toString(),"fixture",base,base+"/rerank","chat","embed","rank");
      var json=new ObjectMapper(); var repository=new AiRepository(settings,json,database.jdbc());
      var models=new AiModelClient(settings,json);
      var documents=mock(AiDocuments.class); var retrieval=mock(AiRetrieval.class);
      var chunk=new Chunk("p1-0",1,0,12,"Alpha is 42.",List.of());
      String document=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString(),message=UUID.randomUUID().toString(),workspace=UUID.randomUUID().toString();
      when(documents.prepare(eq(document),any())).thenReturn(new Content(List.of(chunk),List.of()));
      when(retrieval.search(eq(document),anyString(),anyList())).thenReturn(List.of(chunk));
      var tasks=new TaskStore(database.jdbc(),new TaskSettings(database.taskNamespace(),"all",30,128,3,900));
      var reading=new AiReading(repository,models,documents,retrieval,new AiIntent(models,json));
      var handler=new AiTaskHandler(repository,documents,retrieval,reading,settings,tasks,new cn.edu.pdfworkbench.storage.DocumentStorage(database.jdbc(),objects));
      try(var scope=repository.scope(workspace,()->true)) {
        repository.save(new Document(document,"fixture.pdf","hash",settings.indexVersion(),1,false,"READY","",1));
        repository.save(new Conversation(conversation,document,"title",1,1));
        repository.save(new Message(message,conversation,"assistant","","RUNNING","","ASK","What is Alpha?",null,null,List.of(),2,0));
        var task=tasks.enqueue(workspace,TaskStore.Kind.GENERATE,message,document,conversation);
        var claim=tasks.claim(task.id(),1,"reading");
        var result=handler.execute(claim);tasks.succeed(claim,result);
        assertEquals("COMPLETED",repository.message(message).status());
        assertEquals("Alpha is 42. [p1-0]",repository.message(message).text());
        assertTrue(repository.message(message).sequence()>=2);
        assertEquals(1,repository.message(message).citations().size());
      }
      try(var scope=repository.scope(UUID.randomUUID().toString(),()->true)) {
        assertEquals(404,assertThrows(cn.edu.pdfworkbench.exception.ApiException.class,()->repository.message(message)).status());
      }
    } finally {server.stop(0);}
  }
}
