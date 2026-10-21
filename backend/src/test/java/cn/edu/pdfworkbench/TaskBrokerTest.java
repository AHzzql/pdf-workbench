package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.tasks.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TaskBrokerTest {
  TestServices database;
  AnnotationConfigApplicationContext context;
  TaskStore store; TaskBroker broker; RabbitAdmin admin; TaskSettings settings;
  final ObjectMapper json=new ObjectMapper();

  @BeforeEach void setup() {
    database=new TestServices();
    settings=new TaskSettings(database.taskNamespace(),"web",30,2,3,900);
    store=new TaskStore(database.jdbc(),settings);
    context=new AnnotationConfigApplicationContext();
    context.registerBean(TaskSettings.class,()->settings);
    context.registerBean(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class,database::rabbit);
    context.registerBean(RabbitAdmin.class,()->new RabbitAdmin(context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)));
    context.registerBean(RabbitTemplate.class,()->new RabbitTemplate(context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)));
    context.registerBean(ObjectMapper.class,()->json);
    context.register(TaskBroker.class,TaskBroker.Topology.class); context.refresh();
    broker=context.getBean(TaskBroker.class); admin=context.getBean(RabbitAdmin.class); broker.initialize();
    database.jdbc().execute("CREATE TABLE task_effect(id UUID PRIMARY KEY, value INTEGER NOT NULL)");
  }
  @AfterEach void close() { if(context!=null)context.close(); if(database!=null)database.close(); }
  TaskStore.Task task() {
    String document=UUID.randomUUID().toString();
    return store.enqueue(UUID.randomUUID().toString(),TaskStore.Kind.INDEX,document,document,null);
  }

  @Test void lostPublishConfirmationAndConsumerDisconnectDoNotDuplicateCommittedEffects() throws Exception {
    var task=task(); var event=store.nextEvent(); broker.publish(event);
    var connection=context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class).createConnection();
    var channel=connection.createChannel(false);
    var first=channel.basicGet(settings.queue("index"),false); assertNotNull(first);
    assertEquals(2,first.getProps().getDeliveryMode());
    var body=json.readTree(first.getBody());
    assertEquals(Set.of("taskId","version"),json.convertValue(body,Map.class).keySet());
    assertEquals(task.id(),body.path("taskId").asText());
    var claim=store.claim(task.id(),1,"index");
    store.succeed(claim,()->database.jdbc().update("INSERT INTO task_effect VALUES(?::uuid,1)",task.id()));
    channel.abort();
    channel=connection.createChannel(false);
    com.rabbitmq.client.GetResponse redelivery=null;
    long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
    while(redelivery==null && System.nanoTime()<deadline) { redelivery=channel.basicGet(settings.queue("index"),false); if(redelivery==null)Thread.sleep(20); }
    assertNotNull(redelivery);
    assertTrue(redelivery.getEnvelope().isRedeliver());
    assertNull(store.claim(task.id(),1,"index")); channel.basicAck(redelivery.getEnvelope().getDeliveryTag(),false);
    database.jdbc().update("UPDATE task_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",event.id());
    var repeated=store.nextEvent(); broker.publish(repeated); store.published(repeated);
    var duplicate=channel.basicGet(settings.queue("index"),false); assertNotNull(duplicate);
    assertNull(store.claim(task.id(),1,"index")); channel.basicAck(duplicate.getEnvelope().getDeliveryTag(),false);
    assertEquals(1,database.jdbc().queryForObject("SELECT SUM(value) FROM task_effect",Integer.class));
    channel.close(); connection.close();
  }

  @Test void mandatoryReturnLeavesOutboxPendingUntilRouteIsRestored() throws Exception {
    var task=task(); var event=store.nextEvent();
    admin.removeBinding(new Binding(settings.queue("index"),Binding.DestinationType.QUEUE,settings.namespace(),"index",null));
    assertThrows(Exception.class,()->broker.publish(event)); store.publishFailed(event);
    assertNull(database.jdbc().queryForObject("SELECT published_at FROM task_outbox WHERE id=?",java.sql.Timestamp.class,event.id()));
    assertEquals("QUEUED",store.get(task.id()).state());
    broker.initialize();
    database.jdbc().update("UPDATE task_outbox SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",event.id());
    var resumed=store.nextEvent(); broker.publish(resumed); store.published(resumed);
    assertEquals(1,admin.getQueueInfo(settings.queue("index")).getMessageCount());
  }

  @Test void permanentFailureHasAConfirmedDeadLetterAfterItsDatabaseTransition() throws Exception {
    var task=task();var original=store.nextEvent();broker.publish(original);store.published(original);
    var connection=context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class).createConnection();
    var channel=connection.createChannel(false);
    try {
      var delivery=channel.basicGet(settings.queue("index"),false);assertNotNull(delivery);
      var claim=store.claim(task.id(),1,"index");store.fail(claim,"FIXTURE_PERMANENT","fixture",false);
      assertEquals("FAILED",store.get(task.id()).state());
      channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
      var dead=store.nextEvent();assertEquals("dead",dead.route());broker.publish(dead);store.published(dead);
      var result=channel.basicGet(settings.queue("dead"),false);assertNotNull(result);
      assertEquals(task.id(),json.readTree(result.getBody()).path("taskId").asText());
      channel.basicAck(result.getEnvelope().getDeliveryTag(),false);
    } finally {channel.close();connection.close();}
  }

  @Test void quorumCapacityRejectsPublishWithoutLosingTheBusinessTask() throws Exception {
    var task=task(); var event=store.nextEvent(); boolean rejected=false;
    for(int i=0;i<8;i++) {
      try { broker.publish(new TaskStore.Event(100+i,UUID.randomUUID().toString(),1,"index",UUID.randomUUID().toString(),1)); }
      catch(Exception e) { rejected=true; break; }
    }
    assertTrue(rejected);
    assertThrows(Exception.class,()->broker.publish(event)); store.publishFailed(event);
    assertEquals("QUEUED",store.get(task.id()).state());
    assertNull(database.jdbc().queryForObject("SELECT published_at FROM task_outbox WHERE id=?",java.sql.Timestamp.class,event.id()));
    admin.purgeQueue(settings.queue("index"),false);
    database.jdbc().update("UPDATE task_outbox SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",event.id());
    var resumed=store.nextEvent(); broker.publish(resumed); store.published(resumed);
    assertEquals(1,admin.getQueueInfo(settings.queue("index")).getMessageCount());
  }
}
