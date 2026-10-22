package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.edu.pdfworkbench.tasks.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

class TaskRuntimeRoleTest {
  @Test void webRoleDoesNotPublishRecoverOrConsumeTasks() throws Exception {
    var store=mock(TaskStore.class);var broker=mock(TaskBroker.class);
    var connections=mock(ConnectionFactory.class);var handler=mock(TaskHandler.class);
    var archive=mock(TaskDeadLetters.class);
    var runtime=new TaskRuntime(store,new TaskSettings("pdf-workbench.test.web","web",30,128,3,900),broker,connections,handler,new ObjectMapper(),archive);
    try {
      runtime.start();runtime.dispatch();Thread.sleep(300);
      assertTrue(runtime.isRunning());
      verifyNoInteractions(store,broker,connections,handler,archive);
    } finally {runtime.stop();}
  }
}
