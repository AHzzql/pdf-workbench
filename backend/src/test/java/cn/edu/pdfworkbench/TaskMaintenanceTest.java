package cn.edu.pdfworkbench;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.tasks.*;
import org.junit.jupiter.api.Test;

class TaskMaintenanceTest {
  @Test void workerReconcilesOutsideHeartbeatAndDispatchThreads() {
    var store=mock(TaskStore.class);
    when(store.reconcileCleanup()).thenAnswer(call->{assertEquals("task-cleanup-reconciliation",Thread.currentThread().getName());return 0;});
    var retention=new TaskRetentionSettings(7,30,30);
    var service=new TaskMaintenance(store,new TaskSettings("pdf-workbench.unit","worker",30,128,3,900),retention);
    try {service.start();verify(store,timeout(4000)).reconcileCleanup();verify(store,timeout(1000)).pruneHistory(retention);assertTrue(service.isRunning());}
    finally {service.stop();}
    assertFalse(service.isRunning());
  }
  @Test void webDoesNotReconcile() {
    var store=mock(TaskStore.class);
    var service=new TaskMaintenance(store,new TaskSettings("pdf-workbench.unit","web",30,128,3,900),new TaskRetentionSettings(7,30,30));
    try {service.start();verifyNoInteractions(store);verify(store,after(1200).never()).pruneHistory(any());verifyNoInteractions(store);}
    finally {service.stop();}
  }
}
