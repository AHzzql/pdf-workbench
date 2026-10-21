package cn.edu.pdfworkbench.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class TaskBroker {
  public record Delivery(String taskId, long version) {}
  private final RabbitTemplate template;
  private final AmqpAdmin admin;
  private final TaskSettings settings;
  private final ObjectMapper json;
  private volatile boolean initialized;

  public TaskBroker(RabbitTemplate template, AmqpAdmin admin, TaskSettings settings, ObjectMapper json) {
    this.template = template; this.admin = admin; this.settings = settings; this.json = json;
    template.setMandatory(true);
  }

  public synchronized void initialize() {
    if (!initialized) { admin.initialize(); initialized = true; }
  }

  public void publish(TaskStore.Event event) throws Exception {
    initialize();
    var properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(Long.toString(event.id()));
    var confirmation = new CorrelationData(event.id() + ":" + event.lease());
    try {
      template.send(settings.namespace(), event.route(), new Message(json.writeValueAsBytes(new Delivery(event.task(), event.version())), properties), confirmation);
      if (!confirmation.getFuture().get(5, TimeUnit.SECONDS).isAck() || confirmation.getReturned() != null)
        throw new IllegalStateException("TASK_DELIVERY_UNCONFIRMED");
    } catch (Exception e) { initialized = false; throw e; }
  }

  @Configuration(proxyBeanMethods = false)
  public static class Topology {
    @Bean
    Declarables taskTopology(TaskSettings settings) {
      var declarations = new ArrayList<Declarable>();
      var exchange = new DirectExchange(settings.namespace(), true, false);
      declarations.add(exchange);
      for (String route : List.of("reading","index","cleanup","dead")) {
        var builder = QueueBuilder.durable(settings.queue(route)).quorum().maxLength(settings.maxPending())
            .maxLengthBytes(settings.maxPending() * 256).overflow(QueueBuilder.Overflow.rejectPublish);
        if (!route.equals("dead")) builder.deadLetterExchange(settings.namespace()).deadLetterRoutingKey("dead").deliveryLimit(5);
        var queue = builder.build(); declarations.add(queue);
        declarations.add(BindingBuilder.bind(queue).to(exchange).with(route));
      }
      return new Declarables(declarations);
    }
  }
}
