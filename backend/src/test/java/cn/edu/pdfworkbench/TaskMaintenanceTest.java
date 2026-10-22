package cn.edu.pdfworkbench;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.tasks.*;
import org.junit.jupiter.api.Test;

class TaskMaintenanceTest {
  @Test void workerReconcilesOutsideHeartbeatAndDispatchThreads() {
    var store=mock(TaskStore.class);
    when(store.reconcileCleanup()).thenAnswer(call->{assertEquals("task-cleanup-reconciliation",Thread.currentThread().getName());return 0;});
    var service=new TaskMaintenance(store,new TaskSettings("pdf-workbench.unit","worker",30,128,3,900));
    try {service.start();verify(store,timeout(4000)).reconcileCleanup();assertTrue(service.isRunning());}
    finally {service.stop();}
    assertFalse(service.isRunning());
  }
  @Test void webDoesNotReconcile() {
    var store=mock(TaskStore.class);
    var service=new TaskMaintenance(store,new TaskSettings("pdf-workbench.unit","web",30,128,3,900));
    try {service.start();verify(store,after(1200).never()).reconcileCleanup();}
    finally {service.stop();}
  }
}
