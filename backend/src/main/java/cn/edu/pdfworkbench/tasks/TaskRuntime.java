package cn.edu.pdfworkbench.tasks;

import cn.edu.pdfworkbench.util.Identifiers;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class TaskRuntime implements SmartLifecycle {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskRuntime.class);
  private final TaskStore store;
  private final TaskSettings settings;
  private final TaskBroker broker;
  private final ConnectionFactory connections;
  private final TaskHandler handler;
  private final ObjectMapper json;
  private final TaskDeadLetters deadLetters;
  private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
  private final Map<String,SimpleMessageListenerContainer> containers = new ConcurrentHashMap<>();
  private record Running(TaskStore.Task task, Thread thread) {}
  private final ConcurrentMap<String, Running> running = new ConcurrentHashMap<>();
  private volatile boolean started;
  private long publishBackoffUntil;
  private int publishFailures;

  public TaskRuntime(TaskStore store, TaskSettings settings, TaskBroker broker, ConnectionFactory connections,
      TaskHandler handler, ObjectMapper json,TaskDeadLetters deadLetters) {
    this.store=store; this.settings=settings; this.broker=broker; this.connections=connections;
    this.handler=handler; this.json=json;
    this.deadLetters=deadLetters;
  }

  @Override public boolean isRunning() { return started; }
  @Override public void start() {
    started = true;
    if (!settings.consumes()) return;
    timers.scheduleWithFixedDelay(this::dispatch, 100, 250, TimeUnit.MILLISECONDS);
    timers.scheduleWithFixedDelay(this::maintain, 100, 1000, TimeUnit.MILLISECONDS);
  }

  public void dispatch() {
    if (!started || !settings.consumes() || System.nanoTime()<publishBackoffUntil) return;
    try {
      for (int i=0; i<8; i++) {
        var event = store.nextEvent(); if (event == null) break;
        try { broker.publish(event); store.published(event); publishFailures=0; }
        catch (Exception e) {
          store.publishFailed(event);
          publishBackoffUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.min(30,5L<<Math.min(publishFailures++,3)));
          log.warn("Task delivery pending, outbox={}",event.id()); break;
        }
      }
    } catch (Exception e) { log.debug("Task dispatcher unavailable"); }
  }

  private void maintain() {
    if (!started) return;
    for (var item : running.values()) {
      try { if (!store.heartbeat(item.task())) item.thread().interrupt(); }
      catch (Exception e) { item.thread().interrupt(); }
    }
    try { store.recoverExpired(); store.recoverDeliveries(); }
    catch (Exception e) { log.debug("Task recovery unavailable"); }
    if (settings.consumes()) {
      try { startConsumers(); }
      catch (Exception e) { log.debug("Task consumers unavailable"); }
    }
  }

  private synchronized void startConsumers() {
    if (!started) return;
    broker.initialize();
    for (String route : List.of("reading", "index", "cleanup", "dead")) {
      var existing=containers.get(route);
      if (existing!=null) { if(!existing.isRunning())existing.start(); continue; }
      var container = new SimpleMessageListenerContainer(connections);
      container.setQueueNames(settings.queue(route));
      container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
      container.setConcurrentConsumers(Set.of("cleanup","dead").contains(route) ? 1 : 2);
      container.setPrefetchCount(1); container.setDefaultRequeueRejected(false);
      container.setMissingQueuesFatal(false); container.setRecoveryInterval(2000);
      container.setShutdownTimeout(2000); container.setForceStop(true);
      container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> consume(route, message, channel));
      container.setConsumerStartTimeout(5000);
      containers.put(route,container); container.start();
    }
  }

  private void consume(String route, Message message, Channel channel) throws Exception {
    if(route.equals("dead")) {
      try {
        try { deadLetters.archive(message.getBody()); }
        catch(Exception e) {
          if(started)log.warn("Dead letter archive pending");
          // Explicit returns do not consume RabbitMQ 4.4's failure delivery budget.
          try {Thread.sleep(5000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
          channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,true);
          return;
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
      } catch(Exception e) {
        channel.abort();
        throw e;
      }
      return;
    }
    TaskBroker.Delivery delivery;
    try {
      if (message.getBody().length > 256) throw new IllegalArgumentException();
      delivery = json.readValue(message.getBody(), TaskBroker.Delivery.class);
      Identifiers.requireUuid(delivery.taskId());
      if (delivery.version() < 1) throw new IllegalArgumentException();
    } catch (Exception e) { channel.basicReject(message.getMessageProperties().getDeliveryTag(), false); return; }
    try {
      var task = store.claim(delivery.taskId(), delivery.version(), route);
      if (task != null) {
        var work = new FutureTask<Void>(() -> {
          try { var result = handler.execute(task); store.succeed(task, result); }
          catch (TaskStore.LeaseLost e) { /* 新租约或终态负责后续处理。 */ }
          catch (Exception e) {
            boolean interrupted=Thread.interrupted();
            if (started && !interrupted && store.active(task)) {
              var api=e instanceof ApiException value ? value : null;
              store.fail(task,api==null ? "TASK_FAILED" : api.code(),
                  api==null ? "处理未完成，请稍后重试" : api.getMessage(),api==null || api.status()>=500);
            }
          } finally { Thread.interrupted(); }
          return null;
        });
        // Cancellation targets a task thread, never the shared AMQP consumer thread.
        var thread=Thread.ofVirtual().unstarted(work);
        var execution=new Running(task,thread); running.put(task.id(),execution); thread.start();
        try { work.get(); }
        catch (InterruptedException e) { thread.interrupt(); Thread.currentThread().interrupt(); throw e; }
        finally { running.remove(task.id(),execution); }
      }
      channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    } catch (Exception e) {
      // An unconfirmed database transition must be recovered, not acknowledged or hot-loop requeued.
      channel.abort();
      throw e;
    }
  }

  @Override public synchronized void stop() {
    started = false;
    running.values().forEach(item -> item.thread().interrupt());
    containers.values().forEach(SimpleMessageListenerContainer::stop); containers.clear();
    timers.shutdownNow();
  }
}
