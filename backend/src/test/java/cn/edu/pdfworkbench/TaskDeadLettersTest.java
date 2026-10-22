package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.edu.pdfworkbench.tasks.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

class TaskDeadLettersTest {
  static final TestServices DATABASE=new TestServices();
  final TaskSettings settings=new TaskSettings("pdf-workbench.archive","web",30,128,3,900);
  final ObjectMapper json=new ObjectMapper();
  final TaskDeadLetters archive=new TaskDeadLetters(DATABASE.jdbc(),json,settings);
  @AfterAll static void close(){DATABASE.close();}
  @AfterEach void reset(){DATABASE.jdbc().execute("TRUNCATE task_dead_letters");}
  byte[] payload(String id,long version)throws Exception{return json.writeValueAsBytes(new TaskBroker.Delivery(id,version));}

  @Test void archiveDeduplicatesAndKeepsOnlyBoundedSafeMetadata()throws Exception {
    String id=UUID.randomUUID().toString();byte[] body=payload(id,3);
    archive.archive(body);archive.archive(body);
    assertEquals(1,DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_dead_letters",Integer.class));
    var row=DATABASE.jdbc().queryForMap("SELECT * FROM task_dead_letters");
    assertEquals(Set.of("namespace","fingerprint","body_bytes","task_id","dispatch_version","first_seen","last_seen"),row.keySet());
    assertEquals(id,row.get("task_id").toString());assertEquals(3L,row.get("dispatch_version"));assertEquals(body.length,row.get("body_bytes"));
    assertEquals(64,row.get("fingerprint").toString().length());
  }
  @Test void invalidOversizedAndExtraFieldsDoNotPersistPayloadOrTaskReferences()throws Exception {
    String id=UUID.randomUUID().toString();
    for(String body:List.of("not-json", "x".repeat(257),"{\"taskId\":\""+id+"\",\"version\":1,\"password\":\"fixture-secret\"}",
        "{\"taskId\":\""+id+"\",\"version\":1.5}","{\"taskId\":\""+id+"\",\"version\":0}",
        "{\"taskId\":\"bad\",\"version\":1}","null"))archive.archive(body.getBytes(StandardCharsets.UTF_8));
    assertEquals(7,DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_dead_letters WHERE task_id IS NULL AND dispatch_version IS NULL",Integer.class));
    assertFalse(DATABASE.jdbc().queryForObject("SELECT json_agg(t)::text FROM task_dead_letters t",String.class).contains("fixture-secret"));
  }
  @Test void enclosingTransactionCannotLeadToAcknowledgementBeforeCommit() {
    var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(DATABASE.jdbc().getDataSource()));
    tx.executeWithoutResult(status->assertThrows(IllegalStateException.class,()->archive.archive(new byte[0])));
    assertEquals(0,DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_dead_letters",Integer.class));
  }
  @Test void consumerAcknowledgesOnlyAfterArchiveAndDoesNotInvokeBusinessHandler()throws Exception {
    var handler=mock(TaskHandler.class);var channel=mock(Channel.class);
    var runtime=new TaskRuntime(mock(TaskStore.class),settings,mock(TaskBroker.class),mock(ConnectionFactory.class),handler,json,archive);
    var properties=new MessageProperties();properties.setDeliveryTag(7);
    var message=new Message(payload(UUID.randomUUID().toString(),1),properties);
    doAnswer(call->{assertEquals(1,DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_dead_letters",Integer.class));return null;}).when(channel).basicAck(7,false);
    try {consume(runtime,message,channel);verify(channel).basicAck(7,false);verifyNoInteractions(handler);}
    finally {runtime.stop();}
  }
  @Test void databaseFailureReturnsMessageWithoutAckOrFailureCountIncrement()throws Exception {
    var broken=mock(TaskDeadLetters.class);doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable")).when(broken).archive(any());
    var channel=mock(Channel.class);
    var runtime=new TaskRuntime(mock(TaskStore.class),settings,mock(TaskBroker.class),mock(ConnectionFactory.class),mock(TaskHandler.class),json,broken);
    var properties=new MessageProperties();properties.setDeliveryTag(9);
    try {consume(runtime,new Message(new byte[0],properties),channel);verify(channel).basicNack(9,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());verify(channel,never()).abort();}
    finally {runtime.stop();}
  }
  @Test void shutdownDuringDatabaseBackoffReturnsTheUnarchivedMessage()throws Exception {
    var broken=mock(TaskDeadLetters.class);var entered=new java.util.concurrent.CountDownLatch(1);
    doAnswer(call->{entered.countDown();throw new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable");}).when(broken).archive(any());
    var channel=mock(Channel.class);
    var runtime=new TaskRuntime(mock(TaskStore.class),settings,mock(TaskBroker.class),mock(ConnectionFactory.class),mock(TaskHandler.class),json,broken);
    var properties=new MessageProperties();properties.setDeliveryTag(11);
    var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var thread=Thread.ofVirtual().unstarted(()->{try{consume(runtime,new Message(new byte[0],properties),channel);}catch(Throwable e){failure.set(e);}});
    try {
      thread.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));thread.interrupt();thread.join(3000);
      assertFalse(thread.isAlive());assertNull(failure.get());verify(channel).basicNack(11,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    } finally {thread.interrupt();runtime.stop();}
  }
  static void consume(TaskRuntime runtime,Message message,Channel channel)throws Exception {
    var method=TaskRuntime.class.getDeclaredMethod("consume",String.class,Message.class,Channel.class);method.setAccessible(true);
    method.invoke(runtime,"dead",message,channel);
  }
}
